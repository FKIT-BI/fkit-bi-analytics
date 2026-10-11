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
        + "FROM analytics.machine_current_state c WHERE c.machine_id=?",(rs,row)->{
          String state=rs.getString("state");
          return new CurrentState(state,state,"CALCULATED",rs.getTimestamp("state_since").toInstant(),
              rs.getObject("matched_rule_id",UUID.class),null,null,rs.getString("evaluation_quality"),
              rs.getTimestamp("last_telemetry_observed_at")==null?null:rs.getTimestamp("last_telemetry_observed_at").toInstant(),
              "OFFLINE".equals(state),rs.getTimestamp("data_through").toInstant(),
              rs.getTimestamp("calculated_at").toInstant(),rs.getBoolean("stale"),rs.getBoolean("recalculating"));
        },machineId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Current state not available"));
  }
}
