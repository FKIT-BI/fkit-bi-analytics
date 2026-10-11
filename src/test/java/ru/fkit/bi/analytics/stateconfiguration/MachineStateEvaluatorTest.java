package ru.fkit.bi.analytics.stateconfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MachineStateEvaluatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final MachineStateEvaluator EVALUATOR = new MachineStateEvaluator();
  private static final UUID SIGNAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID RULE = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void unknownInputDoesNotActivateNegatedRuleAndMarksDefaultDegraded() throws Exception {
    JsonNode config = config("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"not-running",
        "priority":10,"targetState":"RUN","enterCondition":{"type":"NOT","condition":{"type":"COMPARE",
        "signalId":"%s","operator":"GT","value":10}},"activationDelayMs":0,"releaseDelayMs":0}]}
        """.formatted(RULE, SIGNAL));
    var result = EVALUATOR.evaluate(config, Map.of(), T0, T0, T0.plusSeconds(1), null, Map.of());
    assertEquals("IDLE", result.state());
    assertEquals("DEGRADED", result.quality());
    assertNull(result.ruleId());
  }

  @Test
  void activationDelayCreatesDurableDeadlineAndUsesEffectiveEventTime() throws Exception {
    JsonNode config = config("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"run",
        "priority":10,"targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s",
        "operator":"GTE","value":100},"activationDelayMs":3000,"releaseDelayMs":0}]}
        """.formatted(RULE, SIGNAL));
    var signal = new MachineStateEvaluator.Signal("DOUBLE", 120d, T0, 30000, "FRESH");
    var waiting = EVALUATOR.evaluate(config, Map.of(SIGNAL, signal), T0, T0, T0, null, Map.of());
    assertEquals("IDLE", waiting.state());
    assertEquals(T0.plusSeconds(3), waiting.deadline().at());
    var ready = EVALUATOR.evaluate(config, Map.of(SIGNAL, signal), T0, T0, T0.plusSeconds(3), null, waiting.timers());
    assertEquals("RUN", ready.state());
    assertEquals(T0.plusSeconds(3), ready.effectiveFrom());
  }

  @Test
  void offlineOverridesRulesAndHysteresisHonorsReleaseDelay() throws Exception {
    JsonNode config = config("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"run",
        "priority":10,"targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s",
        "operator":"GTE","value":100},"exitCondition":{"type":"COMPARE","signalId":"%s",
        "operator":"LT","value":70},"activationDelayMs":0,"releaseDelayMs":5000}]}
        """.formatted(RULE, SIGNAL, SIGNAL));
    var active = new MachineStateEvaluator.Current("RUN", RULE, T0);
    var mid = new MachineStateEvaluator.Signal("DOUBLE", 80d, T0.plusSeconds(1), 30000, "FRESH");
    var held = EVALUATOR.evaluate(config, Map.of(SIGNAL,mid),T0.plusSeconds(1),T0,T0.plusSeconds(1),active,Map.of());
    assertEquals("RUN",held.state());
    var low = new MachineStateEvaluator.Signal("DOUBLE",60d,T0.plusSeconds(2),30000,"FRESH");
    var releasing = EVALUATOR.evaluate(config,Map.of(SIGNAL,low),T0.plusSeconds(2),T0,T0.plusSeconds(2),active,Map.of());
    assertEquals("RUN",releasing.state());
    assertEquals(T0.plusSeconds(7),releasing.deadline().at());
    var released = EVALUATOR.evaluate(config,Map.of(SIGNAL,low),T0.plusSeconds(7),T0,T0.plusSeconds(7),active,releasing.timers());
    assertEquals("IDLE",released.state());
    assertEquals(T0.plusSeconds(7),released.effectiveFrom());
    var offline = EVALUATOR.evaluate(config,Map.of(SIGNAL,low),T0,T0,T0.plusSeconds(60),active,Map.of());
    assertEquals("OFFLINE",offline.state());
    assertNull(offline.ruleId());
  }

  @Test
  void offlineClockUsesReceiptTimeForDelayedObservations() throws Exception {
    JsonNode config = config("{\"defaultState\":\"IDLE\",\"offlineAfterMs\":60000,\"rules\":[]}");
    Instant received = T0;
    Instant oldObservation = T0.minusSeconds(120);
    var online = EVALUATOR.evaluate(config, Map.of(), oldObservation, received, T0, T0.plusSeconds(30), null, Map.of());
    assertEquals("IDLE", online.state());
    var offline = EVALUATOR.evaluate(config, Map.of(), oldObservation, received, T0, T0.plusSeconds(60), null, Map.of());
    assertEquals("OFFLINE", offline.state());
    assertEquals(T0.plusSeconds(60), offline.effectiveFrom());
  }

  @Test
  void longConditionsDoNotCollapseAdjacentValuesAboveDoublePrecision() throws Exception {
    JsonNode config = config("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"exact",
        "priority":10,"targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s",
        "operator":"IN","values":[9007199254740992]},"activationDelayMs":0,"releaseDelayMs":0}]}
        """.formatted(RULE, SIGNAL));
    var signal = new MachineStateEvaluator.Signal("LONG", 9007199254740993L, T0, 60000, "FRESH");
    var result = EVALUATOR.evaluate(config, Map.of(SIGNAL, signal), T0, T0, T0, null, Map.of());
    assertEquals("IDLE", result.state());
    assertNull(result.ruleId());
  }

  private JsonNode config(String value) throws Exception { return JSON.readTree(value); }
}
