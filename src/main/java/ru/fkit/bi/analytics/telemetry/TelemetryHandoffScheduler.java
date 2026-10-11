package ru.fkit.bi.analytics.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TelemetryHandoffScheduler {
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;

  TelemetryHandoffScheduler(JdbcTemplate jdbc, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.meters = meters;
    meters.counter("telemetry.handoff.hints.materialized");
  }

  @Scheduled(fixedDelayString = "${telemetry.handoff.poll-ms:500}")
  void materializeHints() {
    try {
      Integer count = jdbc.queryForObject(
          "SELECT telemetry.materialize_recalculation_hints(?)", Integer.class, 500);
      if (count != null && count > 0) meters.counter("telemetry.handoff.hints.materialized").increment(count);
    } catch (org.springframework.dao.DataAccessException transientFailure) {
      meters.counter("telemetry.handoff.materialization.retries").increment();
    }
  }
}
