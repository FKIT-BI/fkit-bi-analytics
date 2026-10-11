package ru.fkit.bi.analytics.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import ru.fkit.bi.analytics.aggregation.MinuteProjectionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
class SignalProjectionWorker {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SignalProjectionWorker.class);
  private final TelemetryWorkRepository work;
  private final MachineStateEvaluationService stateEvaluation;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;
  private final MinuteProjectionService minuteProjections;
  private final HistoricalStateReplayService historicalStateReplay;
  private final UUID workerId = UUID.randomUUID();

  SignalProjectionWorker(TelemetryWorkRepository work, MachineStateEvaluationService stateEvaluation, JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager, MeterRegistry meters, MinuteProjectionService minuteProjections,
      HistoricalStateReplayService historicalStateReplay) {
    this.work = work;
    this.stateEvaluation=stateEvaluation;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.tx.setTimeout(8);
    this.meters = meters;
    this.minuteProjections = minuteProjections;
    this.historicalStateReplay=historicalStateReplay;
    meters.counter("telemetry.projection.work.completed");
    meters.counter("telemetry.projection.work.retried");
  }

  @Scheduled(fixedDelayString = "${telemetry.projection.poll-ms:500}")
  void processAvailableWork() {
    try {
      for (var item : work.claim(workerId, 4)) {
       try {
        work.startJob(item);
        historicalStateReplay.stage(item);
        minuteProjections.stage(item);
        Boolean published = tx.execute(status -> publish(item));
        if (Boolean.TRUE.equals(published)) {
          work.finishJob(item, true, null);
          meters.counter("telemetry.projection.work.completed").increment();
        }
        else meters.counter("telemetry.projection.work.retried").increment();
       } catch (org.springframework.dao.DataAccessException retryable) {
        LOG.error("Projection work for machine {} failed",item.machineId(),retryable);
        String failureReason=retryable.getClass().getSimpleName()+": "+String.valueOf(retryable.getMostSpecificCause().getMessage());
        try { work.release(item.machineId(), workerId, failureReason); }
        catch (org.springframework.dao.DataAccessException unavailable) { /* lease recovery will reclaim it */ }
        work.retryJob(item, failureReason);
        meters.counter("telemetry.projection.work.retried").increment();
       } catch (RuntimeException failure) {
        String failureReason=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
        try { work.release(item.machineId(), workerId, failureReason); }
        catch (org.springframework.dao.DataAccessException unavailable) { /* lease recovery will reclaim it */ }
        work.retryJob(item, failureReason);
        meters.counter("telemetry.projection.work.failed").increment();
       }
      }
    } catch (org.springframework.dao.DataAccessException unavailable) {
      meters.counter("telemetry.projection.work.retried").increment();
    }
  }

  private boolean publish(TelemetryWorkRepository.WorkItem item) {
    jdbc.execute("SET LOCAL lock_timeout='200ms'");
    jdbc.execute("SET LOCAL statement_timeout='6500ms'");
    LocalDate today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate firstDay = today.minusDays(89);
    // The projection reads retained history through today; future partitions are irrelevant to this read barrier.
    for (int i = 0; i < 90; i++) {
      LocalDate day = firstDay.plusDays(i);
      Boolean acquired = jdbc.queryForObject(
          "SELECT pg_try_advisory_xact_lock_shared(telemetry.raw_partition_day_lock_key(?))",
          Boolean.class, java.sql.Date.valueOf(day));
      Integer active = jdbc.queryForObject(
          "SELECT count(*) FROM telemetry.raw_partition_registry WHERE partition_day=? AND status='ACTIVE'",
          Integer.class, java.sql.Date.valueOf(day));
      if (!Boolean.TRUE.equals(acquired) || active == null || active != 1) {
        work.release(item.machineId(), workerId, "RAW_PARTITION_BARRIER_BUSY");
        return false;
      }
    }
    Integer updated = jdbc.queryForObject("SELECT analytics.refresh_signal_current_for_machine(?,?)",
        Integer.class, item.machineId(), Timestamp.from(item.dirtyTo()));
    stateEvaluation.evaluate(item.machineId(),item.dirtyTo(),Instant.now());
    minuteProjections.publish(item);
    Boolean finished = jdbc.queryForObject("SELECT telemetry.finish_recalculation_work(?,?,?,?)",
        Boolean.class, item.machineId(), workerId, item.generation(), Timestamp.from(item.dirtyTo()));
    if (Boolean.TRUE.equals(finished) && item.recalculationJobId() != null) {
      // Job completion is part of the same commit as the range swap and work checkpoint.
      jdbc.update("UPDATE analytics.state_recalculation_job SET status='SUCCEEDED',progress=1,completed_at=clock_timestamp(),error_code=NULL,error_message=NULL "
          + "WHERE id=? AND status IN ('PENDING','RUNNING')", item.recalculationJobId());
    }
    return updated != null && Boolean.TRUE.equals(finished);
  }
}
