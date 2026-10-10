package ru.fkit.bi.analytics.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("schemaRelease")
public class SchemaReleaseHealthIndicator implements HealthIndicator {
  private final JdbcTemplate jdbcTemplate;
  private final String expectedRelease;
  private final LiquibaseMigrationState migrationState;

  public SchemaReleaseHealthIndicator(
      JdbcTemplate jdbcTemplate,
      LiquibaseMigrationState migrationState,
      @Value("${fkit.schema.expected-release:001-foundation}") String expectedRelease) {
    this.jdbcTemplate = jdbcTemplate;
    this.migrationState = migrationState;
    this.expectedRelease = expectedRelease;
  }

  @Override
  public Health health() {
    if (migrationState.status() == LiquibaseMigrationState.Status.FAILED) {
      return Health.down().withDetail("reason", "MIGRATION_FAILED").build();
    }
    if (migrationState.status() != LiquibaseMigrationState.Status.SUCCEEDED) {
      return Health.down().withDetail("reason", "MIGRATION_NOT_COMPLETED").build();
    }

    final List<String> releases;
    try {
      releases = jdbcTemplate.queryForList(
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE", String.class);
    } catch (DataAccessException exception) {
      return Health.down().withDetail("reason", "RELEASE_CHECK_FAILED").build();
    }

    if (releases.isEmpty()) {
      return Health.down()
          .withDetail("reason", "RELEASE_MISSING")
          .withDetail("expectedRelease", expectedRelease)
          .build();
    }
    String actualRelease = releases.getFirst();
    if (!expectedRelease.equals(actualRelease) || releases.size() != 1) {
      return Health.down()
          .withDetail("reason", "RELEASE_MISMATCH")
          .withDetail("expectedRelease", expectedRelease)
          .withDetail("actualRelease", actualRelease)
          .build();
    }
    return Health.up().withDetail("release", actualRelease).build();
  }
}
