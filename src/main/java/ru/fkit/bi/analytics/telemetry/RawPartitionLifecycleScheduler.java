package ru.fkit.bi.analytics.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.concurrent.atomic.AtomicLong;

@Component
class RawPartitionLifecycleScheduler {
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final AtomicLong partitionWindowSize = new AtomicLong();

  RawPartitionLifecycleScheduler(JdbcTemplate jdbc, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.meters = meters;
    meters.gauge("telemetry.partition.window.size", partitionWindowSize);
  }

  @Scheduled(fixedDelayString = "${telemetry.partition.maintenance-ms:3600000}")
  void maintain() {
    try {
      jdbc.queryForObject("SELECT telemetry.ensure_raw_partition_window()", Integer.class);
      Integer active = jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_partition_registry "
          + "WHERE status='ACTIVE' AND partition_day BETWEEN "
          + "(clock_timestamp() AT TIME ZONE 'UTC')::date - 89 AND "
          + "(clock_timestamp() AT TIME ZONE 'UTC')::date + 7", Integer.class);
      partitionWindowSize.set(active == null ? 0 : active);
      var expired = jdbc.query("SELECT partition_day FROM telemetry.raw_partition_registry "
              + "WHERE partition_day < (clock_timestamp() AT TIME ZONE 'UTC')::date - 89 "
              + "AND NOT telemetry.raw_partition_has_retention_blocker(partition_day) "
              + "ORDER BY partition_day LIMIT 4",
          (rs, row) -> rs.getDate(1).toLocalDate());
      for (var day : expired) {
        Boolean retired = jdbc.queryForObject("SELECT telemetry.retire_raw_partition(?)", Boolean.class,
            Date.valueOf(day));
        if (Boolean.TRUE.equals(retired)) meters.counter("telemetry.partition.retired").increment();
      }
    } catch (org.springframework.dao.DataAccessException transientFailure) {
      meters.counter("telemetry.partition.maintenance.retries").increment();
    }
  }
}
