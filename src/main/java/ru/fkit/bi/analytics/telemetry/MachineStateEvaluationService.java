package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.fkit.bi.analytics.stateconfiguration.MachineStateEvaluator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MachineStateEvaluationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final MachineStateEvaluator evaluator = new MachineStateEvaluator();

  public MachineStateEvaluationService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void evaluate(UUID machineId, Instant dataThrough, Instant detectedAt) {
    MachineInfo machine = jdbc.query("SELECT status,coverage_from FROM equipment.machine WHERE id=?",
        (rs, row) -> new MachineInfo(rs.getString(1), instant(rs, 2)), machineId).stream().findFirst().orElse(null);
    if (machine == null || !"ACTIVE".equals(machine.status()) || machine.coverageFrom() == null) return;
    JsonNode config = jdbc.query("SELECT configuration FROM analytics.machine_state_config WHERE machine_id=?",
        (rs, row) -> parse(rs.getString(1)), machineId).stream().findFirst().orElse(null);
    if (config == null) return;
    Map<UUID, MachineStateEvaluator.Signal> signals = new HashMap<>();
    Instant lastTelemetryObserved = null;
    Instant lastTelemetryReceived = null;
    for (SignalRow row : jdbc.query("SELECT p.signal_id,p.value_type,p.usable_double,p.usable_long,"
        + "p.usable_boolean,p.usable_text,p.usable_observed_at,p.freshness,"
        + "COALESCE(s.stale_after_ms,s.expected_interval_ms*3,60000) stale_ms,"
        + "p.last_received_observed_at,p.last_received_at FROM analytics.signal_current_value p "
        + "JOIN equipment.telemetry_signal s ON s.id=p.signal_id WHERE p.machine_id=?",
        (rs, i) -> new SignalRow(rs.getObject("signal_id", UUID.class), rs.getString("value_type"),
            first(rs.getObject("usable_double"),rs.getObject("usable_long"),rs.getObject("usable_boolean"),rs.getObject("usable_text")),
            instant(rs,"usable_observed_at"),rs.getString("freshness"),rs.getLong("stale_ms"),
            instant(rs,"last_received_observed_at"),instant(rs,"last_received_at")), machineId)) {
      signals.put(row.id(),new MachineStateEvaluator.Signal(row.type(),row.value(),row.observedAt(),row.staleMs(),row.freshness()));
      if(row.lastReceivedObservedAt()!=null&&(lastTelemetryObserved==null||row.lastReceivedObservedAt().isAfter(lastTelemetryObserved)))
        lastTelemetryObserved=row.lastReceivedObservedAt();
      if(row.lastReceivedAt()!=null&&(lastTelemetryReceived==null||row.lastReceivedAt().isAfter(lastTelemetryReceived)))
        lastTelemetryReceived=row.lastReceivedAt();
    }
    MachineStateEvaluator.Current current = jdbc.query("SELECT state,matched_rule_id,state_since "
        + "FROM analytics.machine_current_state WHERE machine_id=?",
        (rs,row)->new MachineStateEvaluator.Current(rs.getString(1),rs.getObject(2,UUID.class),instant(rs,3)),machineId)
        .stream().findFirst().orElse(null);
    Map<UUID,MachineStateEvaluator.Timer> timers=new HashMap<>();
    jdbc.query("SELECT rule_id,enter_true_since,exit_true_since FROM analytics.machine_state_rule_runtime WHERE machine_id=?",
        rs->{UUID id=rs.getObject(1,UUID.class);timers.put(id,new MachineStateEvaluator.Timer(instant(rs,2),instant(rs,3)));},machineId);
    if (!timers.isEmpty()) {
      jdbc.update("DELETE FROM analytics.machine_state_rule_runtime WHERE machine_id=? AND rule_id NOT IN ("
          + "SELECT (rule->>'id')::uuid FROM jsonb_array_elements(?::jsonb->'rules') rule)",
          machineId, config.toString());
      Set<UUID> configuredRules = new java.util.HashSet<>();
      config.path("rules").forEach(rule -> { try { configuredRules.add(UUID.fromString(rule.path("id").asText())); }
        catch (IllegalArgumentException ignored) { } });
      timers.keySet().retainAll(configuredRules);
    }
    Boolean deadlineClaimed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.machine_evaluation_deadline WHERE machine_id=? AND lease_owner IS NOT NULL AND deadline_at<=?)",Boolean.class,machineId,Timestamp.from(detectedAt));
    Instant evaluationAt=Boolean.TRUE.equals(deadlineClaimed)?detectedAt:(lastTelemetryObserved==null?machine.coverageFrom():lastTelemetryObserved);
    MachineStateEvaluator.Result result=evaluator.evaluate(config,signals,lastTelemetryObserved,lastTelemetryReceived,
        machine.coverageFrom(),evaluationAt,current,timers);
    result.timers().forEach((rule,timer)->jdbc.update(
        "INSERT INTO analytics.machine_state_rule_runtime(machine_id,rule_id,enter_true_since,exit_true_since) "
            + "VALUES (?,?,?,?) ON CONFLICT(machine_id,rule_id) DO UPDATE SET "
            + "enter_true_since=EXCLUDED.enter_true_since,exit_true_since=EXCLUDED.exit_true_since",
        machineId,rule,timer.enterSince()==null?null:Timestamp.from(timer.enterSince()),
        timer.exitSince()==null?null:Timestamp.from(timer.exitSince())));
    jdbc.queryForObject("SELECT analytics.record_machine_state(?,?,?,?,?,?,?,?,?)",Boolean.class,
        machineId,result.state(),Timestamp.from(result.effectiveFrom()),Timestamp.from(detectedAt),result.ruleId(),
        result.quality(),lastTelemetryObserved==null?null:Timestamp.from(lastTelemetryObserved),Timestamp.from(dataThrough),Timestamp.from(machine.coverageFrom()));
    if(result.deadline()==null)jdbc.update("DELETE FROM analytics.machine_evaluation_deadline WHERE machine_id=?",machineId);
    else jdbc.update("INSERT INTO analytics.machine_evaluation_deadline(machine_id,deadline_at,reason) VALUES (?,?,?) "
        + "ON CONFLICT(machine_id) DO UPDATE SET deadline_at=EXCLUDED.deadline_at,reason=EXCLUDED.reason,"
        + "lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp()",machineId,Timestamp.from(result.deadline().at()),result.deadline().reason());
  }

  private JsonNode parse(String text){try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
  private static Object first(Object... values){for(Object v:values)if(v!=null)return v;return null;}
  private static Instant instant(java.sql.ResultSet rs,int column)throws java.sql.SQLException{var ts=rs.getTimestamp(column);return ts==null?null:ts.toInstant();}
  private static Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException{var ts=rs.getTimestamp(column);return ts==null?null:ts.toInstant();}
  private record MachineInfo(String status,Instant coverageFrom){}
  private record SignalRow(UUID id,String type,Object value,Instant observedAt,String freshness,long staleMs,
      Instant lastReceivedObservedAt,Instant lastReceivedAt){}
}
