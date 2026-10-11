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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class TelemetryUpgradeAndRetentionTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void preservesLegacyFirstUseAndProtectsWorkReferencedPartition() throws Exception {
    update("db/changelog/db.changelog-through-006.yaml");
    UUID legacySignal = UUID.randomUUID();
    UUID enterprise = UUID.randomUUID();
    UUID machine = UUID.randomUUID();
    try (Connection connection = connect()) {
      try (var insert = connection.prepareStatement("INSERT INTO equipment.enterprise(id,code,name,timezone) VALUES (?,?,'Legacy','UTC')")) {
        insert.setObject(1, enterprise);
        insert.setString(2, "LEGACY_" + enterprise.toString().substring(0, 8).replace("-", "").toUpperCase());
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement("INSERT INTO equipment.machine(id,enterprise_id,code,name) VALUES (?,?, 'M_LEGACY','Legacy')")) {
        insert.setObject(1, machine);
        insert.setObject(2, enterprise);
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement("INSERT INTO equipment.telemetry_signal(id,machine_id,code,name,value_type,signal_kind,semantic_code) VALUES (?,?,'S_LEGACY','Legacy','DOUBLE','GAUGE','ROTATIONAL_SPEED')")) {
        insert.setObject(1, legacySignal);
        insert.setObject(2, machine);
        insert.executeUpdate();
      }
    }
    Instant firstUsed = Instant.parse("2025-03-04T05:06:07.123Z");
    try (Connection connection = connect(); var statement = connection.createStatement()) {
      try (var insert = connection.prepareStatement(
          "INSERT INTO telemetry.signal_usage(signal_id,first_used_at) VALUES (?,?)")) {
        insert.setObject(1, legacySignal);
        insert.setTimestamp(2, Timestamp.from(firstUsed));
        insert.executeUpdate();
      }
    }

    update("db/changelog/db.changelog-master.yaml");
    try (Connection connection = connect(); var statement = connection.createStatement()) {
      try (var row = statement.executeQuery("SELECT first_observed_at,first_received_at FROM telemetry.signal_usage "
          + "WHERE signal_id='" + legacySignal + "'")) {
        assertTrue(row.next());
        assertEquals(firstUsed, row.getTimestamp(1).toInstant());
        assertEquals(firstUsed, row.getTimestamp(2).toInstant());
      }
    }

    LocalDate expiredDay = LocalDate.now(ZoneOffset.UTC).minusDays(100);
    String partition = "raw_measurement_" + expiredDay.format(DateTimeFormatter.BASIC_ISO_DATE);
    enterprise = UUID.randomUUID();
    machine = UUID.randomUUID();
    try (Connection connection = connect()) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE telemetry." + partition + " PARTITION OF telemetry.raw_measurement "
            + "FOR VALUES FROM ('" + expiredDay + " 00:00:00+00') TO ('" + expiredDay.plusDays(1) + " 00:00:00+00')");
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO telemetry.raw_partition_registry(partition_day,status) VALUES (?, 'ACTIVE')")) {
        insert.setObject(1, expiredDay);
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO equipment.enterprise(id,code,name,timezone) VALUES (?,?,'Retention test','UTC')")) {
        insert.setObject(1, enterprise);
        insert.setString(2, "RET_" + enterprise.toString().substring(0, 8).replace("-", "").toUpperCase());
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO equipment.machine(id,enterprise_id,code,name) VALUES (?,?, 'M_RETENTION','Retention test')")) {
        insert.setObject(1, machine);
        insert.setObject(2, enterprise);
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement("INSERT INTO telemetry.recalculation_work "
          + "(machine_id,dirty_from,dirty_to,status) VALUES (?,?,?,'RETRY')")) {
        insert.setObject(1, machine);
        insert.setObject(2, Timestamp.from(expiredDay.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(60)));
        insert.setObject(3, Timestamp.from(expiredDay.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(120)));
        insert.executeUpdate();
      }
      try (var statement = connection.createStatement()) {
        assertTrue(booleanValue(statement, "SELECT telemetry.raw_partition_has_retention_blocker('" + expiredDay + "')"));
        assertFalse(booleanValue(statement, "SELECT telemetry.retire_raw_partition('" + expiredDay + "')"));
        assertNotNull(scalar(statement, "SELECT to_regclass('telemetry." + partition + "')"));
        statement.executeUpdate("UPDATE telemetry.recalculation_work SET status='IDLE' WHERE machine_id='" + machine + "'");
        assertTrue(booleanValue(statement, "SELECT telemetry.retire_raw_partition('" + expiredDay + "')"));
        assertEquals(0, integer(statement, "SELECT count(*) FROM telemetry.raw_partition_registry WHERE partition_day='" + expiredDay + "'"));
      }
    }
  }

  private void update(String changelog) throws Exception {
    try (Connection connection = connect()) {
      var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try { new Liquibase(changelog, new ClassLoaderResourceAccessor(), database).update(new Contexts(), new LabelExpression()); }
      finally { database.close(); }
    }
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static boolean booleanValue(java.sql.Statement statement, String sql) throws Exception {
    try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getBoolean(1); }
  }
  private static String scalar(java.sql.Statement statement, String sql) throws Exception {
    try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
  }
  private static int integer(java.sql.Statement statement, String sql) throws Exception {
    try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); }
  }
}
