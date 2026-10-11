package ru.fkit.bi.analytics.telemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TelemetryWorkRepository {
  private final JdbcTemplate jdbc;

  public TelemetryWorkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<WorkItem> claim(UUID owner, int limit) {
    return jdbc.query("SELECT w.machine_id,w.dirty_from,GREATEST(w.dirty_to,COALESCE(c.data_through,w.dirty_to)) dirty_to,w.generation,w.checkpoint_at,w.attempt,w.recalculation_job_id "
            + "FROM telemetry.claim_recalculation_work(?,?,interval '30 seconds') w LEFT JOIN analytics.machine_current_state c ON c.machine_id=w.machine_id",
        (rs, row) -> new WorkItem(rs.getObject("machine_id", UUID.class),
            rs.getTimestamp("dirty_from").toInstant(), rs.getTimestamp("dirty_to").toInstant(),
            rs.getLong("generation"), rs.getTimestamp("checkpoint_at") == null
                ? null : rs.getTimestamp("checkpoint_at").toInstant(), rs.getInt("attempt"),
            rs.getObject("recalculation_job_id", UUID.class)), owner, limit);
  }

  public boolean finish(WorkItem work, UUID owner, Instant checkpointAt) {
    Boolean result = jdbc.queryForObject("SELECT telemetry.finish_recalculation_work(?,?,?,?)",
        Boolean.class, work.machineId(), owner, work.generation(), Timestamp.from(checkpointAt));
    return Boolean.TRUE.equals(result);
  }

  public boolean release(UUID machineId, UUID owner, String error) {
    Boolean result = jdbc.queryForObject("SELECT telemetry.release_recalculation_work(?,?,?)",
        Boolean.class, machineId, owner, error);
    return Boolean.TRUE.equals(result);
  }

  public void startJob(WorkItem work) {
    if (work.recalculationJobId() == null) return;
    jdbc.update("UPDATE analytics.state_recalculation_job SET status='RUNNING',started_at=COALESCE(started_at,clock_timestamp()),progress=GREATEST(progress,0.1),error_code=NULL,error_message=NULL "
        + "WHERE id=? AND status='PENDING'", work.recalculationJobId());
  }

  public void retryJob(WorkItem work,String error) {
    if(work.recalculationJobId()==null)return;
    jdbc.update("UPDATE analytics.state_recalculation_job SET status='PENDING',error_code='RETRY_PENDING',error_message=?,completed_at=NULL "
        + "WHERE id=? AND status='RUNNING'",error,work.recalculationJobId());
  }

  public void finishJob(WorkItem work, boolean success, String error) {
    if (work.recalculationJobId() == null) return;
    jdbc.update("UPDATE analytics.state_recalculation_job SET status=?,progress=?,completed_at=clock_timestamp(),error_code=?,error_message=? WHERE id=? AND status IN ('PENDING','RUNNING')",
        success ? "SUCCEEDED" : "FAILED", success ? 1 : 0, success ? null : "RECALCULATION_FAILED",
        success ? null : error, work.recalculationJobId());
    jdbc.update("UPDATE analytics.machine_current_state SET recalculating=EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=? AND w.status<>'IDLE') WHERE machine_id=?",
        work.machineId(), work.machineId());
  }

  public record WorkItem(UUID machineId, Instant dirtyFrom, Instant dirtyTo,
                         long generation, Instant checkpointAt, int attempt, UUID recalculationJobId) { }
}
