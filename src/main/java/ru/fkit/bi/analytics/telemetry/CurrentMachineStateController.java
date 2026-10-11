package ru.fkit.bi.analytics.telemetry;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.CurrentState;

import java.util.UUID;

@RestController
public class CurrentMachineStateController {
  private final JdbcTemplate jdbc;
  public CurrentMachineStateController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  @GetMapping("/api/v1/machines/{machineId}/current-state")
  public CurrentState current(@PathVariable UUID machineId) {
    if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.machine WHERE id=?)",
        Boolean.class,machineId))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Machine not found");
    return jdbc.query("SELECT c.state,c.state_since,c.matched_rule_id,c.evaluation_quality,"
        + "c.last_telemetry_observed_at,c.data_through,c.calculated_at,"
        + "(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=c.machine_id AND h.consumed_at IS NULL) "
        + "OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status<>'IDLE')) stale, "
        + "EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status='RUNNING' "
        + "AND w.lease_until>clock_timestamp()) recalculating "
        + ",e.effective_state,e.effective_from,e.state_source,e.state_incident_id,e.reason_incident_id,e.loss_reason_id,e.calculated_state effective_calculated_state,"
        + "r.id reason_id,r.code reason_code,r.name reason_name,r.category reason_category,r.color reason_color,r.status reason_status "
        + "FROM analytics.machine_current_state c LEFT JOIN analytics.machine_effective_state_interval e ON e.machine_id=c.machine_id AND e.effective_to IS NULL "
        + "LEFT JOIN analytics.loss_reason r ON r.id=e.loss_reason_id WHERE c.machine_id=?",(rs,row)->{
          String state=rs.getString("state"),effective=rs.getString("effective_state");Object reason=null;if(rs.getObject("reason_id")!=null){java.util.Map<String,Object> summary=new java.util.LinkedHashMap<>();summary.put("id",rs.getObject("reason_id",UUID.class));summary.put("code",rs.getString("reason_code"));summary.put("name",rs.getString("reason_name"));summary.put("category",rs.getString("reason_category"));summary.put("color",rs.getString("reason_color"));summary.put("status",rs.getString("reason_status"));reason=summary;}
          return new CurrentState(effective==null?state:effective,state,effective==null?"CALCULATED":rs.getString("state_source"),rs.getTimestamp("effective_from")==null?rs.getTimestamp("state_since").toInstant():rs.getTimestamp("effective_from").toInstant(),
              rs.getObject("matched_rule_id",UUID.class),rs.getObject("state_incident_id",UUID.class),rs.getObject("reason_incident_id",UUID.class),reason,rs.getString("evaluation_quality"),
              rs.getTimestamp("last_telemetry_observed_at")==null?null:rs.getTimestamp("last_telemetry_observed_at").toInstant(),
              "OFFLINE".equals(rs.getString("effective_calculated_state")==null?state:rs.getString("effective_calculated_state")),rs.getTimestamp("data_through").toInstant(),
              rs.getTimestamp("calculated_at").toInstant(),rs.getBoolean("stale"),rs.getBoolean("recalculating"));
        },machineId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Current state not available"));
  }
}
