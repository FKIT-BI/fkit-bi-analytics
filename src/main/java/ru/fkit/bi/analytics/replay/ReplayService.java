package ru.fkit.bi.analytics.replay;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.telemetry.HistoricalStateReplayService;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static ru.fkit.bi.analytics.replay.ReplayModels.*;

@Service
public class ReplayService {
  private final JdbcTemplate jdbc;
  private final HistoricalStateReplayService stateReplay;
  public ReplayService(JdbcTemplate jdbc,HistoricalStateReplayService stateReplay) { this.jdbc = jdbc;this.stateReplay=stateReplay; }

  @Transactional
  public Job create(UUID machineId, Create request) {
    if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.machine WHERE id=?)", Boolean.class, machineId))) {
      throw new BusinessException(404, "NOT_FOUND", "Machine not found");
    }
    String scope = request == null ? null : request.scope();
    if (!"PERIOD".equals(scope) && !"ALL".equals(scope)) throw invalid("scope must be PERIOD or ALL");
    Instant requestedFrom = request.from(), requestedTo = request.to();
    Instant effectiveFrom, effectiveTo;
    if ("PERIOD".equals(scope)) {
      if (requestedFrom == null || requestedTo == null || !requestedFrom.isBefore(requestedTo)) throw invalid("PERIOD requires a non-empty from/to range");
      Instant earliest = Instant.now().minus(90, ChronoUnit.DAYS);
      if (requestedFrom.isBefore(earliest) || requestedTo.isAfter(Instant.now())) throw invalid("PERIOD must be within the retained 90-day history");
      effectiveFrom = requestedFrom.truncatedTo(ChronoUnit.MINUTES);
      effectiveTo = ceilMinute(requestedTo);
      List<Instant> publishedThrough=jdbc.query("SELECT data_through FROM analytics.machine_current_state WHERE machine_id=?",(rs,i)->rs.getTimestamp(1).toInstant(),machineId);
      if(!publishedThrough.isEmpty())effectiveTo=max(effectiveTo,ceilMinute(publishedThrough.getFirst()));
    } else {
      if (requestedFrom != null || requestedTo != null) throw invalid("ALL does not accept from/to");
      List<Instant[]> coverage = jdbc.query("SELECT coverage_from,data_through FROM analytics.machine_current_state WHERE machine_id=?",
          (rs, i) -> new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()}, machineId);
      if (coverage.isEmpty()) throw new BusinessException(409, "ANALYTICS_NOT_READY", "Machine has no published analytics coverage");
      effectiveFrom = coverage.getFirst()[0].truncatedTo(ChronoUnit.MINUTES);
      effectiveTo = coverage.getFirst()[1].truncatedTo(ChronoUnit.MINUTES);
    }
    if (!effectiveFrom.isBefore(effectiveTo)) throw invalid("Replay range contains no complete UTC minute");
    stateReplay.validate(machineId,effectiveFrom,"ALL".equals(scope));
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO analytics.state_recalculation_job(id,machine_id,scope,requested_from,requested_to,effective_from,effective_to,status) "
              + "VALUES (?,?,?,?,?,?,?,'PENDING')", id, machineId, scope,
          requestedFrom == null ? null : Timestamp.from(requestedFrom), requestedTo == null ? null : Timestamp.from(requestedTo),
          Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo));
    } catch (DuplicateKeyException active) {
      throw new BusinessException(409, "RECALCULATION_ALREADY_RUNNING", "A recalculation is already running for this machine");
    }
    jdbc.update("INSERT INTO telemetry.recalculation_work(machine_id,dirty_from,dirty_to,status,recalculation_job_id) "
            + "VALUES (?,?,?,'PENDING',?) ON CONFLICT(machine_id) DO UPDATE SET "
            + "dirty_from=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_from ELSE LEAST(telemetry.recalculation_work.dirty_from,EXCLUDED.dirty_from) END, "
            + "dirty_to=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_to ELSE GREATEST(telemetry.recalculation_work.dirty_to,EXCLUDED.dirty_to) END, "
            + "generation=telemetry.recalculation_work.generation+1, "
            + "status=CASE WHEN telemetry.recalculation_work.lease_owner IS NULL THEN 'PENDING' ELSE telemetry.recalculation_work.status END, "
            + "recalculation_job_id=EXCLUDED.recalculation_job_id,updated_at=clock_timestamp()",
        machineId, Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo), id);
    jdbc.update("UPDATE analytics.machine_current_state SET recalculating=TRUE WHERE machine_id=?", machineId);
    return get(id);
  }

  public Job get(UUID id) {
    return jdbc.query("SELECT id,machine_id,scope,requested_from,requested_to,effective_from,effective_to,status,progress,created_at,started_at,completed_at,error_code,error_message "
            + "FROM analytics.state_recalculation_job WHERE id=?", rs -> {
      if (!rs.next()) return null;
      String code = rs.getString("error_code");
      return new Job(rs.getObject("id", UUID.class), rs.getObject("machine_id", UUID.class), rs.getString("scope"),
          instant(rs, "requested_from"), instant(rs, "requested_to"), instant(rs, "effective_from"), instant(rs, "effective_to"),
          rs.getString("status"), rs.getBigDecimal("progress").doubleValue(), instant(rs, "created_at"),
          instant(rs, "started_at"), instant(rs, "completed_at"), code == null ? null : new JobError(code, rs.getString("error_message")));
    }, id);
  }

  public List<Job> list(UUID machineId, String status, int limit,Instant beforeAt,UUID beforeId) {
    if (limit < 1 || limit > 101) throw invalid("limit must be between 1 and 101");
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.machine WHERE id=?)",Boolean.class,machineId)))
      throw new BusinessException(404,"NOT_FOUND","Machine not found");
    if (status != null && !List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(status)) throw invalid("Unsupported job status");
    String sql = "SELECT id FROM analytics.state_recalculation_job WHERE machine_id=? "
        + (status == null ? "" : "AND status=? ")+(beforeAt==null?"":"AND (created_at<? OR (created_at=? AND id<?)) ")+"ORDER BY created_at DESC,id DESC LIMIT ?";
    java.util.List<Object> args=new java.util.ArrayList<>();args.add(machineId);if(status!=null)args.add(status);
    if(beforeAt!=null){args.add(Timestamp.from(beforeAt));args.add(Timestamp.from(beforeAt));args.add(beforeId);}
    args.add(limit);
    return jdbc.query(sql, (rs, i) -> get(rs.getObject(1, UUID.class)), args.toArray());
  }

  @Transactional
  public Job cancel(UUID id) {
    List<UUID> machineIds=jdbc.query("SELECT machine_id FROM analytics.state_recalculation_job WHERE id=? AND status='PENDING' FOR UPDATE",
        (rs,i)->rs.getObject(1,UUID.class),id);
    if(machineIds.isEmpty()){
      if(get(id)==null)throw new BusinessException(404,"NOT_FOUND","Recalculation job not found");
      throw new BusinessException(409,"RECALCULATION_NOT_CANCELLABLE","Only a pending recalculation can be cancelled");
    }
    UUID machineId=machineIds.getFirst();
    List<Boolean> leases=jdbc.query("SELECT lease_owner IS NOT NULL FROM telemetry.recalculation_work WHERE machine_id=? AND recalculation_job_id=? FOR UPDATE",
        (rs,i)->rs.getBoolean(1),machineId,id);
    if(!leases.isEmpty()&&leases.getFirst())throw new BusinessException(409,"RECALCULATION_NOT_CANCELLABLE","Recalculation is already leased by a worker");
    jdbc.update("UPDATE analytics.state_recalculation_job SET status='CANCELLED',completed_at=clock_timestamp(),error_code='CANCELLED',error_message='Cancelled before worker claim' WHERE id=? AND status='PENDING'",id);
    jdbc.update("UPDATE telemetry.recalculation_work SET recalculation_job_id=NULL,updated_at=clock_timestamp() WHERE machine_id=? AND recalculation_job_id=? AND lease_owner IS NULL",machineId,id);
    return get(id);
  }

  private BusinessException invalid(String message) { return new BusinessException(422, "VALIDATION_FAILED", message); }
  private static Instant ceilMinute(Instant instant) {
    Instant floor = instant.truncatedTo(ChronoUnit.MINUTES);
    return floor.equals(instant) ? floor : floor.plus(1, ChronoUnit.MINUTES);
  }
  private static Instant max(Instant left,Instant right){return left.isAfter(right)?left:right;}
  private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
  }
}
