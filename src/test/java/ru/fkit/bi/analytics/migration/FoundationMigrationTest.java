package ru.fkit.bi.analytics.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class FoundationMigrationTest {
  private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void cleanAndLegacyDatabasesReceiveIdempotentFoundationWithoutChangingLegacyMetadata() throws Exception {
    String cleanUrl = postgres.getJdbcUrl();
    apply(cleanUrl);
    List<String> cleanSchemas;
    try (Connection connection = connect(cleanUrl); Statement statement = connection.createStatement()) {
      cleanSchemas = schemas(statement);
      assertEquals("001-foundation", scalar(statement,
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE"));
      assertEquals(26, Integer.parseInt(scalar(statement,
          "SELECT count(*) FROM equipment.signal_semantic")));
      assertEquals(Set.of("TEMPERATURE", "PRESSURE", "FLOW_RATE", "LEVEL", "HUMIDITY",
              "ROTATIONAL_SPEED", "LINEAR_SPEED", "VIBRATION", "VOLTAGE", "CURRENT", "POWER",
              "ENERGY", "TORQUE", "FORCE", "POSITION", "DISTANCE", "PRODUCTION_COUNT",
              "SCRAP_COUNT", "CYCLE_COUNT", "OPERATING_FLAG", "READY_FLAG", "ALARM_FLAG",
              "MACHINE_MODE", "QUALITY_CODE", "LOAD_PERCENT", "CUSTOM"),
          codes(statement, "SELECT code FROM equipment.signal_semantic"));
      assertEquals(8, Integer.parseInt(scalar(statement,
          "SELECT count(*) FROM equipment.measurement_unit")));
      assertEquals(Set.of("PCS", "METER", "KILOGRAM", "LITER", "CELSIUS", "PERCENT", "RPM", "BAR"),
          codes(statement, "SELECT code FROM equipment.measurement_unit"));
      assertEquals(Set.of(
              "PCS|Piece|pcs|COUNT", "METER|Meter|m|LENGTH", "KILOGRAM|Kilogram|kg|MASS",
              "LITER|Liter|L|VOLUME", "CELSIUS|Degree Celsius|°C|TEMPERATURE",
              "PERCENT|Percent|%|RATIO", "RPM|Revolutions per minute|rpm|ROTATIONAL_SPEED",
              "BAR|Bar|bar|PRESSURE"),
          tuples(statement, "SELECT code || '|' || name || '|' || symbol || '|' || quantity_code "
              + "FROM equipment.measurement_unit"));
      assertEquals(8, Integer.parseInt(scalar(statement,
          "SELECT count(*) FROM equipment.measurement_unit WHERE status = 'ACTIVE'")));
      assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
          "INSERT INTO equipment.measurement_unit (code, name, status) VALUES ('INVALID', 'Invalid', 'UNKNOWN')"));
      assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
          "INSERT INTO equipment.signal_semantic (code, name, status) VALUES ('INVALID', 'Invalid', 'UNKNOWN')"));
      assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
          "INSERT INTO equipment.schema_release (singleton, release) VALUES (FALSE, '002-next')"));
      assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
          "INSERT INTO equipment.schema_release (singleton, release) VALUES (TRUE, '002-next')"));
    }
    apply(cleanUrl);
    try (Connection connection = connect(cleanUrl); Statement statement = connection.createStatement()) {
      assertEquals(cleanSchemas, schemas(statement));
      assertEquals("001-foundation", scalar(statement,
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE"));
      assertEquals(26, Integer.parseInt(scalar(statement,
          "SELECT count(*) FROM equipment.signal_semantic")));
      assertEquals(8, Integer.parseInt(scalar(statement,
          "SELECT count(*) FROM equipment.measurement_unit")));
    }

    String legacyUrl = createLegacyDatabase();
    try (Connection connection = connect(legacyUrl); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE fkit_bi_schema_marker (id INTEGER PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL)");
      statement.execute("INSERT INTO fkit_bi_schema_marker VALUES "
          + "(1, '2020-01-01T00:00:00Z'), (2, '2020-01-02T00:00:00Z')");
      statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY, "
          + "version VARCHAR(50), description VARCHAR(200), type VARCHAR(20), script VARCHAR(1000), "
          + "checksum INTEGER, installed_by VARCHAR(100), installed_on TIMESTAMPTZ NOT NULL, "
          + "execution_time INTEGER NOT NULL, success BOOLEAN NOT NULL)");
      statement.execute("INSERT INTO flyway_schema_history VALUES "
          + "(1, '1', 'bootstrap', 'SQL', 'V1__bootstrap.sql', 11, 'fkit_bi', "
          + "'2020-01-01T00:00:00Z', 12, TRUE), "
          + "(2, '2', 'equipment', 'SQL', 'V2__equipment.sql', 22, 'fkit_bi', "
          + "'2020-01-02T00:00:00Z', 34, TRUE)");
    }
    apply(legacyUrl);
    try (Connection connection = connect(legacyUrl); Statement statement = connection.createStatement()) {
      assertEquals(2, Integer.parseInt(scalar(statement, "SELECT count(*) FROM fkit_bi_schema_marker")));
      assertEquals("1|2020-01-01 00:00:00", scalar(statement,
          "SELECT id || '|' || to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') "
              + "FROM fkit_bi_schema_marker WHERE id = 1"));
      assertEquals(2, Integer.parseInt(scalar(statement, "SELECT count(*) FROM flyway_schema_history")));
      assertEquals("1|V1__bootstrap.sql|11|true", scalar(statement,
          "SELECT version || '|' || script || '|' || checksum || '|' || success "
              + "FROM flyway_schema_history WHERE installed_rank = 1"));
      assertEquals("2|V2__equipment.sql|22|true", scalar(statement,
          "SELECT version || '|' || script || '|' || checksum || '|' || success "
              + "FROM flyway_schema_history WHERE installed_rank = 2"));
      assertEquals("001-foundation", scalar(statement,
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE"));
      assertTrue(schemas(statement).containsAll(List.of("equipment", "telemetry", "analytics", "incident", "generator")));
    }
    apply(legacyUrl);
    try (Connection connection = connect(legacyUrl); Statement statement = connection.createStatement()) {
      assertEquals(2, Integer.parseInt(scalar(statement, "SELECT count(*) FROM fkit_bi_schema_marker")));
      assertEquals(2, Integer.parseInt(scalar(statement, "SELECT count(*) FROM flyway_schema_history")));
      assertEquals(1, Integer.parseInt(scalar(statement, "SELECT count(*) FROM equipment.schema_release")));
    }
  }

  private String createLegacyDatabase() throws Exception {
    try (Connection connection = DriverManager.getConnection(adminUrl(), postgres.getUsername(), postgres.getPassword());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE fkit_legacy");
    }
    return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/fkit_legacy");
  }

  private String adminUrl() {
    return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/postgres");
  }

  private Connection connect(String url) throws Exception {
    return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
  }

  private void apply(String url) throws Exception {
    try (Connection connection = connect(url)) {
      Database database = DatabaseFactory.getInstance()
          .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try {
        new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
            .update(new Contexts(), new LabelExpression());
      } finally {
        database.close();
      }
    }
  }

  private List<String> schemas(Statement statement) throws Exception {
    try (ResultSet result = statement.executeQuery(
        "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('equipment', 'telemetry', 'analytics', 'incident', 'generator') ORDER BY schema_name")) {
      var values = new java.util.ArrayList<String>();
      while (result.next()) values.add(result.getString(1));
      return values;
    }
  }

  private String scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("Expected a row for query: " + sql);
      return result.getString(1);
    }
  }

  private Set<String> codes(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      var values = new java.util.HashSet<String>();
      while (result.next()) values.add(result.getString(1));
      return values.stream().collect(Collectors.toUnmodifiableSet());
    }
  }

  private Set<String> tuples(Statement statement, String sql) throws Exception {
    return codes(statement, sql);
  }
}
