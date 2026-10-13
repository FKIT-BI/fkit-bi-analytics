package ru.fkit.bi.analytics.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.fkit.bi.analytics.stateconfiguration.StateConfigurationService;

/** The demo seed must land as ready, activatable equipment whose scenarios reference only their own signals. */
@Testcontainers
class DemoEnterpriseSeedTest {
  private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void seedCreatesReadyDemoEnterprisesOnce() throws Exception {
    String url = createDatabase("demo_seed");
    update(url, new Contexts());
    JdbcTemplate jdbc = jdbc(url);

    assertEquals(List.of("DEMO_COSMETICS", "DEMO_WAREHOUSE"),
        jdbc.queryForList("SELECT code FROM equipment.enterprise ORDER BY code", String.class));
    assertEquals(4, count(jdbc, "SELECT count(*) FROM equipment.shift"));
    assertEquals(5, count(jdbc, "SELECT count(*) FROM equipment.production_line"));
    assertEquals(16, count(jdbc, "SELECT count(*) FROM equipment.machine"));
    assertEquals(List.of("SPARE_DOSER", "TUBE_FILLER_NEW"), jdbc.queryForList(
        "SELECT code FROM equipment.machine WHERE status='DISABLED' AND coverage_from IS NULL ORDER BY code", String.class));
    assertEquals(1, count(jdbc, "SELECT count(*) FROM equipment.machine WHERE line_id IS NULL"));
    assertEquals(18, count(jdbc, "SELECT count(*) FROM analytics.loss_reason"));
    assertEquals(6, count(jdbc, "SELECT count(*) FROM generator.generation_scenario WHERE status='DISABLED'"));

    // Coverage starts at local midnight seven days back so a week of history is inside analytics.
    assertEquals(14, count(jdbc, "SELECT count(*) FROM equipment.machine WHERE status='ACTIVE' AND coverage_from = "
        + "(date_trunc('day', now() AT TIME ZONE 'Europe/Moscow') - interval '7 days') AT TIME ZONE 'Europe/Moscow'"));
    assertEquals(14, count(jdbc, "SELECT count(*) FROM telemetry.recalculation_work w "
        + "JOIN equipment.machine m ON m.id=w.machine_id AND w.dirty_from=m.coverage_from"));

    StateConfigurationService states = new StateConfigurationService(jdbc, new ObjectMapper(),
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
    for (UUID machine : jdbc.queryForList("SELECT id FROM equipment.machine WHERE status='ACTIVE'", UUID.class)) {
      assertEquals(List.of(), states.activationErrors(machine), "activation errors for " + machine);
      assertEquals(List.of(), states.activationWarnings(machine), "activation warnings for " + machine);
    }
    assertEquals(0, count(jdbc, "SELECT count(*) FROM (SELECT machine_id,semantic_code FROM equipment.telemetry_signal "
        + "WHERE signal_kind='COUNTER' AND status='ACTIVE' GROUP BY 1,2 HAVING count(*)>1) duplicated"));

    // Every behavior targets a signal of the machine listed in the same scenario.
    assertEquals(0, count(jdbc, "WITH refs AS ("
        + " SELECT s.id scenario_id,(b->>'signalId')::uuid signal_id FROM generator.generation_scenario s,"
        + " jsonb_array_elements(s.definition->'machineTargets') t, jsonb_array_elements(t->'baseBehaviors') b"
        + " UNION ALL SELECT s.id,(o->>'signalId')::uuid FROM generator.generation_scenario s,"
        + " jsonb_array_elements(s.definition->'phases') p, jsonb_array_elements(p->'behaviorOverrides') o)"
        + " SELECT count(*) FROM refs r LEFT JOIN equipment.telemetry_signal ts ON ts.id=r.signal_id"
        + " LEFT JOIN generator.generation_scenario_machine sm ON sm.scenario_id=r.scenario_id AND sm.machine_id=ts.machine_id"
        + " WHERE sm.machine_id IS NULL"));

    long changesets = count(jdbc, "SELECT count(*) FROM databasechangelog");
    update(url, new Contexts());
    assertEquals(changesets, count(jdbc, "SELECT count(*) FROM databasechangelog"));
    assertEquals(16, count(jdbc, "SELECT count(*) FROM equipment.machine"));
  }

  @Test
  void seedIsSkippedWhenTheDemoCodeIsAlreadyTaken() throws Exception {
    String url = createDatabase("demo_seed_taken");
    update(url, new Contexts("test"));
    JdbcTemplate jdbc = jdbc(url);
    jdbc.update("INSERT INTO equipment.enterprise(id,code,name,timezone) VALUES (?,'DEMO_COSMETICS','Existing','UTC')", UUID.randomUUID());

    update(url, new Contexts());

    assertEquals(1, count(jdbc, "SELECT count(*) FROM equipment.enterprise"));
    assertEquals(0, count(jdbc, "SELECT count(*) FROM equipment.machine"));
    assertEquals(7, count(jdbc, "SELECT count(*) FROM databasechangelog WHERE id LIKE '015-%' AND exectype='MARK_RAN'"));
  }

  private static long count(JdbcTemplate jdbc, String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    assertTrue(value != null);
    return value;
  }

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
  }

  private static void update(String url, Contexts contexts) throws Exception {
    try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
      var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).update(contexts, new LabelExpression());
    }
  }

  private static String createDatabase(String name) throws Exception {
    try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
  }
}
