package ru.fkit.bi.analytics.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

@Testcontainers
class EpicOneMigrationContractTest {
  private static final String CHANGELOG = "db/changelog/db.changelog-epic-1.yaml";
  private static final String BCRYPT_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7pvh8uKTVdK6Cj6B9N6bB6e";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void cleanDatabaseReceivesOnlyTheIdempotentEpicOneContract() throws Exception {
    String url = createDatabase("epic_one_clean");

    apply(url);
    try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
      assertEquals(
          List.of("analytics", "equipment", "generator", "identity", "incident", "telemetry"),
          applicationSchemas(statement));
      assertEquals("002-authentication", scalar(statement,
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE"));
      assertEquals("5", scalar(statement, "SELECT count(*) FROM databasechangelog"));
      assertEquals("26", scalar(statement, "SELECT count(*) FROM equipment.signal_semantic"));
      assertEquals("8", scalar(statement, "SELECT count(*) FROM equipment.measurement_unit"));

      assertNull(nullableScalar(statement, "SELECT to_regclass('equipment.enterprise')::text"));
      assertNull(nullableScalar(statement, "SELECT to_regclass('telemetry.raw_measurement')::text"));

      insertUser(statement, UUID.randomUUID(), "root.user", "Root user", true);
      assertEquals("1", scalar(statement, "SELECT count(*) FROM identity.users WHERE is_bootstrap"));

      assertThrows(SQLException.class,
          () -> insertUser(statement, UUID.randomUUID(), "Upper.User", "Upper user", false));
      assertThrows(SQLException.class,
          () -> insertUser(statement, UUID.randomUUID(), "second.root", "Second root", true));
      assertThrows(SQLException.class,
          () -> statement.executeUpdate(
              "UPDATE identity.users SET is_bootstrap = FALSE WHERE username = 'root.user'"));
    }

    apply(url);
    try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
      assertEquals("5", scalar(statement, "SELECT count(*) FROM databasechangelog"));
      assertEquals("1", scalar(statement, "SELECT count(*) FROM equipment.schema_release"));
      assertEquals("1", scalar(statement, "SELECT count(*) FROM identity.users"));
    }
  }

  @Test
  void legacyMetadataAndRowsSurviveTheEpicOneMigration() throws Exception {
    String url = createDatabase("epic_one_legacy");
    try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE fkit_bi_schema_marker (id INTEGER PRIMARY KEY, note TEXT NOT NULL)");
      statement.execute("INSERT INTO fkit_bi_schema_marker VALUES (1, 'keep-marker')");
      statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY, "
          + "version VARCHAR(50), script VARCHAR(1000), checksum INTEGER, success BOOLEAN NOT NULL)");
      statement.execute("INSERT INTO flyway_schema_history VALUES "
          + "(1, '1', 'V1__bootstrap.sql', 42, TRUE)");
    }

    apply(url);
    apply(url);

    try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
      assertEquals("keep-marker", scalar(statement,
          "SELECT note FROM fkit_bi_schema_marker WHERE id = 1"));
      assertEquals("1|V1__bootstrap.sql|42|true", scalar(statement,
          "SELECT installed_rank || '|' || script || '|' || checksum || '|' || success "
              + "FROM flyway_schema_history"));
      assertEquals("002-authentication", scalar(statement,
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE"));
      assertEquals("5", scalar(statement, "SELECT count(*) FROM databasechangelog"));
    }
  }

  private String createDatabase(String name) throws Exception {
    try (Connection connection = DriverManager.getConnection(adminUrl(), postgres.getUsername(), postgres.getPassword());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/" + name);
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

  private void insertUser(Statement statement, UUID id, String username, String displayName,
      boolean bootstrap) throws SQLException {
    statement.executeUpdate("INSERT INTO identity.users "
        + "(id, username, display_name, password_hash, is_bootstrap) VALUES ('"
        + id + "', '" + username + "', '" + displayName + "', '" + BCRYPT_HASH + "', "
        + bootstrap + ")");
  }

  private List<String> applicationSchemas(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery(
        "SELECT schema_name FROM information_schema.schemata "
            + "WHERE schema_name IN ('equipment', 'telemetry', 'analytics', 'incident', 'generator', 'identity') "
            + "ORDER BY schema_name")) {
      List<String> schemas = new ArrayList<>();
      while (result.next()) schemas.add(result.getString(1));
      return schemas;
    }
  }

  private String scalar(Statement statement, String sql) throws SQLException {
    try (ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("Expected a row for query: " + sql);
      return result.getString(1);
    }
  }

  private String nullableScalar(Statement statement, String sql) throws SQLException {
    return scalar(statement, sql);
  }
}
