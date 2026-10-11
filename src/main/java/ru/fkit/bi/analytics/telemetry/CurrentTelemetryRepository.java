package ru.fkit.bi.analytics.telemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.Currency;
import ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.Response;
import ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.SignalValue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class CurrentTelemetryRepository {
  private static final int MAX_UNFILTERED_SIGNALS = 500;
  private final JdbcTemplate jdbc;

  public CurrentTelemetryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Response read(UUID machineId, List<UUID> signalIds, boolean featuredOnly, boolean includeDisabled) {
    if (!Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM equipment.machine WHERE id=?)", Boolean.class, machineId))) return null;
    StringBuilder sql = new StringBuilder("SELECT s.id,s.component_id,s.code,s.name,s.value_type,s.signal_kind,"
        + "s.semantic_code,s.unit_code,s.featured,s.display_order,s.status,p.last_received_observed_at,"
        + "p.last_received_at,p.received_double,p.received_long,p.received_boolean,p.received_text,"
        + "p.received_quality,p.received_flags,p.usable_observed_at,p.usable_double,p.usable_long,"
        + "p.usable_boolean,p.usable_text,p.freshness,p.data_through,p.calculated_at "
        + "FROM equipment.telemetry_signal s LEFT JOIN analytics.signal_current_value p ON p.signal_id=s.id "
        + "WHERE s.machine_id=? ");
    List<Object> args = new ArrayList<>();
    args.add(machineId);
    if (!includeDisabled) sql.append("AND s.status='ACTIVE' ");
    if (featuredOnly) sql.append("AND s.featured=TRUE ");
    if (signalIds != null && !signalIds.isEmpty()) {
      sql.append("AND s.id IN (").append(String.join(",", java.util.Collections.nCopies(signalIds.size(), "?"))).append(") ");
      args.addAll(signalIds);
    }
    sql.append("ORDER BY s.display_order NULLS LAST,s.code,s.id LIMIT ?");
    args.add(MAX_UNFILTERED_SIGNALS + 1);
    List<SignalValue> values = jdbc.query(sql.toString(), this::mapSignal, args.toArray());
    boolean truncated = values.size() > MAX_UNFILTERED_SIGNALS;
    if (truncated) values = new ArrayList<>(values.subList(0, MAX_UNFILTERED_SIGNALS));
    Currency currency = currency(machineId);
    return new Response(machineId, values, currency, truncated);
  }

  private Currency currency(UUID machineId) {
    return jdbc.queryForObject("SELECT min(p.data_through),min(p.calculated_at),"
            + "(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=? AND h.consumed_at IS NULL) "
            + "OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=? AND w.status<>'IDLE') "
            + "OR coalesce(bool_or(p.signal_id IS NULL),false)) AS stale, "
            + "EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=? AND w.status='RUNNING' "
            + "AND w.lease_until>clock_timestamp()) AS recalculating "
            + "FROM equipment.telemetry_signal s LEFT JOIN analytics.signal_current_value p ON p.signal_id=s.id "
            + "WHERE s.machine_id=? AND s.status='ACTIVE'",
        (rs, row) -> {
          Instant dataThrough = instant(rs, 1);
          Instant calculatedAt = instant(rs, 2);
          boolean stale = rs.getBoolean(3);
          boolean recalculating = rs.getBoolean(4);
          return new Currency(dataThrough, calculatedAt, stale, recalculating);
        }, machineId, machineId, machineId, machineId);
  }

  private SignalValue mapSignal(ResultSet rs, int row) throws SQLException {
    Instant receivedAt = instant(rs, "last_received_observed_at");
    String freshness = rs.getString("freshness");
    if (freshness == null) freshness = "NO_DATA";
    int flags = rs.getObject("received_flags") == null ? 0 : rs.getInt("received_flags");
    List<String> flagNames = new ArrayList<>();
    if ((flags & 1) != 0) flagNames.add("OUT_OF_RANGE");
    if ((flags & 2) != 0) flagNames.add("CLOCK_SKEW");
    return new SignalValue(rs.getObject("id", UUID.class), nullableUuid(rs, "component_id"),
        rs.getString("code"), rs.getString("name"), rs.getString("value_type"), rs.getString("signal_kind"),
        rs.getString("semantic_code"), rs.getString("unit_code"), rs.getBoolean("featured"),
        (Integer) rs.getObject("display_order"), rs.getString("status"),
        value(rs, "received_double", "received_long", "received_boolean", "received_text"),
        receivedAt, instant(rs, "last_received_at"), quality(rs.getObject("received_quality")),
        List.copyOf(flagNames), value(rs, "usable_double", "usable_long", "usable_boolean", "usable_text"),
        instant(rs, "usable_observed_at"), freshness, instant(rs, "data_through"),
        instant(rs, "calculated_at"), "STALE".equals(freshness));
  }

  private Object value(ResultSet rs, String number, String integer, String bool, String text) throws SQLException {
    Object value = rs.getObject(number);
    if (value != null) return value;
    value = rs.getObject(integer);
    if (value != null) return value;
    value = rs.getObject(bool);
    if (value != null) return value;
    return rs.getObject(text);
  }

  private String quality(Object quality) {
    if (quality == null) return null;
    return switch (((Number) quality).intValue()) { case 0 -> "GOOD"; case 1 -> "UNCERTAIN"; default -> "BAD"; };
  }

  private UUID nullableUuid(ResultSet rs, String col) throws SQLException { return rs.getObject(col, UUID.class); }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
  private Instant instant(ResultSet rs, int column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
