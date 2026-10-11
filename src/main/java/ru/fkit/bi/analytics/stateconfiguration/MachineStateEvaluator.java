package ru.fkit.bi.analytics.stateconfiguration;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic typed evaluator for the closed state-rule AST. */
public final class MachineStateEvaluator {
  public enum Truth { TRUE, FALSE, UNKNOWN }
  public record Signal(String valueType, Object usableValue, Instant observedAt, long staleAfterMs, String freshness) { }
  public record Timer(Instant enterSince, Instant exitSince) { }
  public record Current(String state, UUID ruleId, Instant stateSince) { }
  public record Deadline(Instant at, String reason) { }
  public record Result(String state, UUID ruleId, String quality, Instant effectiveFrom,
      Map<UUID, Timer> timers, Deadline deadline) { }

  public Result evaluate(JsonNode config, Map<UUID, Signal> signals, Instant lastTelemetry,
      Instant coverageFrom, Instant at, Current current, Map<UUID, Timer> previousTimers) {
    return evaluate(config, signals, lastTelemetry, lastTelemetry, coverageFrom, at, current, previousTimers);
  }

  public Result evaluate(JsonNode config, Map<UUID, Signal> signals, Instant lastTelemetryObserved,
      Instant lastTelemetryReceived, Instant coverageFrom, Instant at, Current current,
      Map<UUID, Timer> previousTimers) {
    long offlineAfter = config.path("offlineAfterMs").asLong(60_000);
    Instant offlineAt = (lastTelemetryReceived == null ? coverageFrom : lastTelemetryReceived).plusMillis(offlineAfter);
    if (!at.isBefore(offlineAt)) {
      return new Result("OFFLINE", null, "COMPLETE",
          current != null && "OFFLINE".equals(current.state()) ? current.stateSince() : offlineAt,
          Map.of(), null);
    }

    List<Rule> rules = new ArrayList<>();
    Map<UUID, Timer> nextTimers = new HashMap<>();
    boolean degraded = false;
    for (JsonNode node : config.path("rules")) {
      UUID id = UUID.fromString(node.path("id").asText());
      Truth enter = condition(node.path("enterCondition"), signals);
      Truth exit = node.hasNonNull("exitCondition") ? condition(node.path("exitCondition"), signals)
          : negate(enter);
      degraded |= enter == Truth.UNKNOWN || exit == Truth.UNKNOWN;
      Timer old = previousTimers.getOrDefault(id, new Timer(null, null));
      Instant enterSince = enter == Truth.TRUE ? (old.enterSince() == null ? at : old.enterSince()) : null;
      Instant exitSince = exit == Truth.TRUE ? (old.exitSince() == null ? at : old.exitSince()) : null;
      nextTimers.put(id, new Timer(enterSince, exitSince));
      long activation = node.path("activationDelayMs").asLong(0);
      long release = node.path("releaseDelayMs").asLong(0);
      Instant entered = enterSince == null ? null : enterSince.plusMillis(activation);
      boolean ready = entered != null && !entered.isAfter(at);
      rules.add(new Rule(id, node.path("targetState").asText(), node.path("priority").asInt(),
          activation, release, enter, exit, enterSince, exitSince, entered, ready));
    }
    rules.sort(Comparator.comparingInt(Rule::priority).reversed());

    Rule active = current == null || current.ruleId() == null ? null
        : rules.stream().filter(r -> r.id().equals(current.ruleId())).findFirst().orElse(null);
    boolean activeHeld = false;
    if (active != null) {
      Timer timer = nextTimers.get(active.id());
      Instant releaseAt = timer.exitSince() == null ? null : timer.exitSince().plusMillis(active.releaseDelay());
      activeHeld = releaseAt == null || releaseAt.isAfter(at);
      if (activeHeld) active = new Rule(active.id(), active.target(), active.priority(), active.activationDelay(),
          active.releaseDelay(), active.enter(), active.exit(), active.enterSince(), active.exitSince(),
          active.effectiveAt(), active.ready());
    }

    Rule selected = null;
    if (activeHeld && active != null) {
      int activePriority = active.priority();
      selected = rules.stream().filter(r -> r.ready() && r.priority() > activePriority).findFirst().orElse(active);
    } else {
      selected = rules.stream().filter(Rule::ready).findFirst().orElse(null);
    }
    String state = selected == null ? config.path("defaultState").asText("IDLE") : selected.target();
    UUID ruleId = selected == null ? null : selected.id();
    Instant effectiveAt = selected == null
        ? active != null && active.exitSince() != null
            ? active.exitSince().plusMillis(active.releaseDelay())
            : current == null ? coverageFrom : at
        : selected.ready() && (current == null || !selected.id().equals(current.ruleId()))
            ? selected.effectiveAt() : current == null ? coverageFrom : current.stateSince();

    List<Deadline> deadlines = new ArrayList<>();
    if (offlineAt.isAfter(at)) deadlines.add(new Deadline(offlineAt, "MACHINE_OFFLINE"));
    for (Signal signal : signals.values()) {
      if (signal.observedAt() != null && signal.staleAfterMs() > 0) {
        Instant staleAt = signal.observedAt().plusMillis(signal.staleAfterMs());
        if (staleAt.isAfter(at)) deadlines.add(new Deadline(staleAt, "SIGNAL_STALE"));
      }
    }
    for (Rule r : rules) {
      Timer timer = nextTimers.get(r.id());
      if (timer.enterSince() != null) {
        Instant d = timer.enterSince().plusMillis(r.activationDelay());
        if (d.isAfter(at)) deadlines.add(new Deadline(d, "RULE_ACTIVATION"));
      }
      if (timer.exitSince() != null) {
        Instant d = timer.exitSince().plusMillis(r.releaseDelay());
        if (d.isAfter(at)) deadlines.add(new Deadline(d, "RULE_RELEASE"));
      }
    }
    Deadline next = deadlines.stream().min(Comparator.comparing(Deadline::at)).orElse(null);
    return new Result(state, ruleId, degraded ? "DEGRADED" : "COMPLETE", effectiveAt,
        Map.copyOf(nextTimers), next);
  }

  public Truth condition(JsonNode node, Map<UUID, Signal> signals) {
    return switch (node.path("type").asText()) {
      case "ALL" -> {
        boolean unknown = false;
        boolean falseValue = false;
        for (JsonNode child : node.path("conditions")) {
          Truth result = condition(child, signals);
          unknown |= result == Truth.UNKNOWN;
          falseValue |= result == Truth.FALSE;
        }
        yield falseValue ? Truth.FALSE : unknown ? Truth.UNKNOWN : Truth.TRUE;
      }
      case "ANY" -> {
        boolean unknown = false;
        boolean trueValue = false;
        for (JsonNode child : node.path("conditions")) {
          Truth result = condition(child, signals);
          unknown |= result == Truth.UNKNOWN;
          trueValue |= result == Truth.TRUE;
        }
        yield trueValue ? Truth.TRUE : unknown ? Truth.UNKNOWN : Truth.FALSE;
      }
      case "NOT" -> negate(condition(node.path("condition"), signals));
      case "COMPARE" -> compare(node, signals);
      default -> Truth.UNKNOWN;
    };
  }

  private Truth compare(JsonNode n, Map<UUID, Signal> signals) {
    UUID id;
    try { id = UUID.fromString(n.path("signalId").asText()); }
    catch (IllegalArgumentException e) { return Truth.UNKNOWN; }
    Signal signal = signals.get(id);
    if (signal == null || signal.usableValue() == null || signal.observedAt() == null
        || !"FRESH".equals(signal.freshness())) return Truth.UNKNOWN;
    String op = n.path("operator").asText();
    Object actual = signal.usableValue();
    JsonNode value = n.get("value");
    if (actual instanceof Number a && value != null && value.isNumber()) {
      if (isIntegralValue(a) && value.isIntegralNumber()) {
        BigDecimal x = new BigDecimal(a.toString());
        BigDecimal y = value.decimalValue();
        BigDecimal tolerance = BigDecimal.valueOf(n.path("tolerance").asDouble(0));
        int comparison = x.compareTo(y);
        return switch (op) {
          case "EQ" -> truth(x.subtract(y).abs().compareTo(tolerance) <= 0);
          case "NE" -> truth(x.subtract(y).abs().compareTo(tolerance) > 0);
          case "GT" -> truth(comparison > 0);
          case "GTE" -> truth(comparison >= 0);
          case "LT" -> truth(comparison < 0);
          case "LTE" -> truth(comparison <= 0);
          case "BETWEEN" -> truth(x.compareTo(n.path("lower").decimalValue()) >= 0
              && x.compareTo(n.path("upper").decimalValue()) <= 0);
          case "IN" -> containsNumber(n.path("values"), x) ? Truth.TRUE : Truth.FALSE;
          default -> Truth.UNKNOWN;
        };
      }
      double x = a.doubleValue(), y = value.asDouble();
      double tolerance = n.path("tolerance").asDouble(0);
      return switch (op) {
        case "EQ" -> Math.abs(x - y) <= tolerance ? Truth.TRUE : Truth.FALSE;
        case "NE" -> Math.abs(x - y) > tolerance ? Truth.TRUE : Truth.FALSE;
        case "GT" -> x > y ? Truth.TRUE : Truth.FALSE;
        case "GTE" -> x >= y ? Truth.TRUE : Truth.FALSE;
        case "LT" -> x < y ? Truth.TRUE : Truth.FALSE;
        case "LTE" -> x <= y ? Truth.TRUE : Truth.FALSE;
        case "BETWEEN" -> x >= n.path("lower").asDouble() && x <= n.path("upper").asDouble() ? Truth.TRUE : Truth.FALSE;
        case "IN" -> containsNumber(n.path("values"), a) ? Truth.TRUE : Truth.FALSE;
        default -> Truth.UNKNOWN;
      };
    }
    if (actual instanceof Boolean bool && value != null && value.isBoolean()) {
      boolean equal = bool == value.asBoolean();
      return switch (op) { case "EQ" -> truth(equal); case "NE" -> truth(!equal); default -> Truth.UNKNOWN; };
    }
    if (actual instanceof String text) {
      boolean equal = value != null && value.isTextual() && text.equals(value.asText());
      if ("IN".equals(op)) {
        for (JsonNode item : n.path("values")) if (item.isTextual() && text.equals(item.asText())) return Truth.TRUE;
        return Truth.FALSE;
      }
      return switch (op) { case "EQ" -> truth(equal); case "NE" -> truth(!equal); default -> Truth.UNKNOWN; };
    }
    return Truth.UNKNOWN;
  }

  private boolean containsNumber(JsonNode values, BigDecimal actual) {
    for (JsonNode value : values) if (value.isNumber() && value.decimalValue().compareTo(actual) == 0) return true;
    return false;
  }
  private boolean containsNumber(JsonNode values, Number actual) {
    for (JsonNode value : values) if (value.isNumber() && Double.compare(value.asDouble(), actual.doubleValue()) == 0) return true;
    return false;
  }
  private boolean isIntegralValue(Number value) {
    return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
        || value instanceof java.math.BigInteger;
  }
  private static Truth truth(boolean b) { return b ? Truth.TRUE : Truth.FALSE; }
  private static Truth negate(Truth value) { return value == Truth.UNKNOWN ? value : value == Truth.TRUE ? Truth.FALSE : Truth.TRUE; }

  private record Rule(UUID id, String target, int priority, long activationDelay, long releaseDelay,
      Truth enter, Truth exit, Instant enterSince, Instant exitSince, Instant effectiveAt, boolean ready) { }
}
