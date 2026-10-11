package ru.fkit.bi.analytics.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MachineDeadlineScheduler {
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final UUID owner = UUID.randomUUID();

  MachineDeadlineScheduler(JdbcTemplate jdbc, MeterRegistry meters) { this.jdbc=jdbc; this.meters=meters; }

  @Scheduled(fixedDelayString = "${telemetry.deadline.poll-ms:250}")
  void enqueueDue() {
    try {
      Integer count=jdbc.queryForObject("SELECT analytics.enqueue_due_machine_deadlines(?,?)",Integer.class,owner,100);
      if(count!=null&&count>0)meters.counter("telemetry.deadline.enqueued").increment(count);
    } catch (org.springframework.dao.DataAccessException transientFailure) {
      meters.counter("telemetry.deadline.retries").increment();
    }
  }
}
