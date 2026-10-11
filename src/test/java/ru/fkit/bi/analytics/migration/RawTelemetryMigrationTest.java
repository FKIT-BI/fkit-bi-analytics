package ru.fkit.bi.analytics.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class RawTelemetryMigrationTest {
  private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void installsTypedAppendOnlySchemaAndBoundedUtcPartitionLifecycle() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      var database = DatabaseFactory.getInstance()
          .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try {
        new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
            .update(new Contexts(), new LabelExpression());
      } finally {
        database.close();
      }
    }

    try (Connection connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
         Statement statement = connection.createStatement()) {
      assertEquals(97, integer(statement,
          "SELECT count(*) FROM telemetry.raw_partition_registry WHERE status='ACTIVE'"));
      assertEquals(97, integer(statement,
          "SELECT count(*) FROM pg_partition_tree('telemetry.raw_measurement') WHERE level=1"));
      assertEquals(0, integer(statement,
          "SELECT count(*) FROM pg_constraint WHERE conrelid='telemetry.raw_measurement'::regclass "
              + "AND contype IN ('p','u','f')"));
      assertEquals(1, integer(statement,
          "SELECT count(*) FROM pg_indexes WHERE schemaname='telemetry' "
              + "AND tablename='raw_measurement' AND indexdef LIKE '%(signal_id, observed_at)%'"));
      assertTrue(scalar(statement,
          "SELECT pg_get_functiondef('telemetry.retire_raw_partition(date)'::regprocedure)")
          .contains("pg_try_advisory_xact_lock"));
      assertTrue(scalar(statement,
          "SELECT pg_get_functiondef('telemetry.raw_partition_has_retention_blocker(date)'::regprocedure)")
          .contains("recalculation_hint"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "INSERT INTO telemetry.raw_measurement(ingest_batch_id,ordinal,signal_id,observed_at) "
              + "VALUES (1,0,'00000000-0000-0000-0000-000000000001',clock_timestamp())"));
    }
  }

  private static int integer(Statement statement, String sql) throws Exception {
    try (var result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("Expected a row for query: " + sql);
      return result.getInt(1);
    }
  }

  private static String scalar(Statement statement, String sql) throws Exception {
    try (var result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("Expected a row for query: " + sql);
      return result.getString(1);
    }
  }
}
