package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Database-backed golden slice: accepted raw + durable handoff -> projections -> current read API. */
@Testcontainers
@SpringBootTest(properties = {
    "telemetry.handoff.poll-ms=3600000",
    "telemetry.projection.poll-ms=3600000",
    "telemetry.deadline.poll-ms=3600000",
    "telemetry.partition.maintenance-ms=3600000"
})
@AutoConfigureMockMvc
class TelemetryGoldenSliceTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("fkit.auth.jwt-secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired TelemetryHandoffScheduler handoff;
  @Autowired SignalProjectionWorker projectionWorker;

  @Test @WithMockUser
  void acceptedRawAndDurableHintBecomeCurrentSignalAndStateReads() throws Exception {
    String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
    UUID enterprise=create("/api/v1/enterprises", Map.of("code","PLANT_"+suffix,"name","Plant","timezone","UTC"));
    UUID line=create("/api/v1/enterprises/"+enterprise+"/lines",
        Map.of("code","LINE_"+suffix,"name","Line"));
    UUID machine=create("/api/v1/enterprises/"+enterprise+"/machines",
        Map.of("code","M_"+suffix,"name","Machine","lineId",line,"parameters",java.util.List.of()));
    UUID signal=create("/api/v1/machines/"+machine+"/telemetry-signals",Map.of(
        "code","SPEED_"+suffix,"name","Speed","valueType","DOUBLE","signalKind","GAUGE",
        "semanticCode","ROTATIONAL_SPEED","staleAfterMs",60000,"featured",true));
    String config="""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"name":"Running","priority":10,
        "targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s","operator":"GTE","value":100},
        "activationDelayMs":0,"releaseDelayMs":0}]}
        """.formatted(signal);
    mvc.perform(put("/api/v1/machines/"+machine+"/state-configuration")
        .contentType(MediaType.APPLICATION_JSON).content(config)).andExpect(status().isOk());
    mvc.perform(put("/api/v1/machines/"+machine+"/status")
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());

    Instant observed=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    String producer="golden-"+suffix, batchId=UUID.randomUUID().toString();
    Long id=jdbc.queryForObject("INSERT INTO telemetry.ingest_batch(producer_id,producer_batch_id,producer_sequence,"
        + "payload_hash,mode,measurement_count,min_observed_at,max_observed_at) VALUES (?,?,1,?,1,1,?,?) RETURNING id",
        Long.class,producer,batchId,new byte[32],Timestamp.from(observed),Timestamp.from(observed));
    jdbc.update("INSERT INTO telemetry.signal_usage(signal_id,first_observed_at,first_received_at) VALUES (?,?,clock_timestamp()) "
        + "ON CONFLICT(signal_id) DO NOTHING",signal,Timestamp.from(observed));
    jdbc.update("INSERT INTO telemetry.raw_measurement(ingest_batch_id,ordinal,signal_id,observed_at,value_double,source_quality,validation_flags) "
        + "VALUES (?,0,?,?,120,0,0)",id,signal,Timestamp.from(observed));
    jdbc.update("INSERT INTO telemetry.recalculation_hint(ingest_batch_id,machine_id,from_observed_at,to_observed_at) "
        + "VALUES (?,?,?,?)",id,machine,Timestamp.from(observed),Timestamp.from(observed.plusMillis(1)));

    Instant deadline=Instant.now().plusSeconds(15);
    String signalPath="/api/v1/machines/"+machine+"/telemetry/current";
    while(Instant.now().isBefore(deadline)) {
      handoff.materializeHints();
      projectionWorker.processAvailableWork();
      var result=mvc.perform(get(signalPath).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("golden")))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      JsonNode body=json.readTree(result);
      if(body.path("items").size()>0 && body.path("items").get(0).path("lastReceivedValue").asDouble(-1)==120d) break;
      Thread.sleep(100);
    }
    var currentResponse=mvc.perform(get(signalPath)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var failedWork=jdbc.queryForMap("SELECT status,last_error FROM telemetry.recalculation_work WHERE machine_id=?",machine);
    assertEquals(120d,json.readTree(currentResponse).path("items").get(0).path("lastReceivedValue").asDouble(-1),
        "current response="+currentResponse+", projection work="+failedWork);
    mvc.perform(get(signalPath)).andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].freshness").value("FRESH"));
    var stateResponse=mvc.perform(get("/api/v1/machines/"+machine+"/current-state")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals("RUN",json.readTree(stateResponse).path("calculatedState").asText(),stateResponse+" timeline="+jdbc.queryForList("SELECT effective_from,effective_to,state FROM analytics.machine_state_interval WHERE machine_id=? ORDER BY effective_from",machine)+" raw="+jdbc.queryForList("SELECT observed_at,value_double FROM telemetry.raw_measurement WHERE signal_id=?",signal));
    mvc.perform(get("/api/v1/machines/"+machine+"/current-state")).andExpect(status().isOk())
        .andExpect(jsonPath("$.dataThrough").exists())
        .andExpect(jsonPath("$.calculatedAt").exists())
        .andExpect(jsonPath("$.stale").exists())
        .andExpect(jsonPath("$.recalculating").exists());

    Instant historyBucket=Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(),300)*300).minusSeconds(600);
    Instant historicalObserved=historyBucket.plusSeconds(15);
    jdbc.update("UPDATE equipment.machine SET coverage_from=? WHERE id=?",Timestamp.from(historyBucket),machine);
    jdbc.update("UPDATE analytics.machine_current_state SET coverage_from=? WHERE machine_id=?",Timestamp.from(historyBucket),machine);
    Long historicalBatch=jdbc.queryForObject("INSERT INTO telemetry.ingest_batch(producer_id,producer_batch_id,producer_sequence,payload_hash,mode,measurement_count,min_observed_at,max_observed_at) VALUES (?, ?,2,?,1,1,?,?) RETURNING id",
        Long.class,producer,"golden-history-"+suffix,new byte[32],Timestamp.from(historicalObserved),Timestamp.from(historicalObserved));
    jdbc.update("INSERT INTO telemetry.raw_measurement(ingest_batch_id,ordinal,signal_id,observed_at,value_double,source_quality,validation_flags) VALUES (?,0,?,?,110,0,0)",historicalBatch,signal,Timestamp.from(historicalObserved));
    jdbc.update("INSERT INTO telemetry.recalculation_hint(ingest_batch_id,machine_id,from_observed_at,to_observed_at) VALUES (?,?,?,?)",
        historicalBatch,machine,Timestamp.from(historicalObserved),Timestamp.from(historicalObserved.plusMillis(1)));
    handoff.materializeHints();
    projectionWorker.processAvailableWork();
    assertEquals(1,jdbc.queryForObject("SELECT sample_count FROM analytics.signal_numeric_rollup_1m WHERE signal_id=? AND bucket_start=?",Integer.class,signal,Timestamp.from(historyBucket)));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start=?",Integer.class,machine,Timestamp.from(historyBucket)));
    mvc.perform(get("/api/v1/machines/"+machine+"/telemetry/series").param("signalId",signal.toString())
        .param("from",historyBucket.toString()).param("to",historyBucket.plusSeconds(300).toString()).param("resolution","5m"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.points[0].sampleCount").value(1))
        .andExpect(jsonPath("$.points[0].avg").value(110));
    mvc.perform(get("/api/v1/analytics/metrics/timeseries").param("scope","MACHINE").param("scopeId",machine.toString())
        .param("from",historyBucket.toString()).param("to",historyBucket.plusSeconds(300).toString()).param("resolution","5m"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.points[0].metrics.availability").value(1));
    var intervalsResponse=mvc.perform(get("/api/v1/machines/"+machine+"/state-intervals").param("from",historicalObserved.toString())
        .param("to",historyBucket.plusSeconds(60).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals("RUN",json.readTree(intervalsResponse).path("items").get(0).path("effectiveState").asText(),
        intervalsResponse+" timeline="+jdbc.queryForList("SELECT effective_from,effective_to,state FROM analytics.machine_state_interval WHERE machine_id=? ORDER BY effective_from",machine));
    mvc.perform(post("/api/v1/enterprises/"+enterprise+"/shifts").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("code","DAY_"+suffix,"name","Day shift","startLocalTime","00:00",
            "endLocalTime","23:59","validFrom",LocalDate.now(ZoneOffset.UTC),"sortOrder",1))))
        .andExpect(status().isCreated());
    mvc.perform(get("/api/v1/machines/"+machine+"/chronometry").param("shiftDate",LocalDate.now(ZoneOffset.UTC).toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.occurrences.length()",org.hamcrest.Matchers.greaterThan(0)));

    var jobResponse=mvc.perform(post("/api/v1/machines/"+machine+"/state-recalculations").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("scope","PERIOD","from",historicalObserved.minusSeconds(1),"to",historicalObserved.plusSeconds(1)))))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    UUID jobId=UUID.fromString(json.readTree(jobResponse.getResponse().getContentAsString()).path("id").asText());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM telemetry.recalculation_work WHERE machine_id=? AND recalculation_job_id=?",Integer.class,machine,jobId));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/state-recalculations/"+jobId))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM analytics.state_recalculation_job WHERE id=?",String.class,jobId));
    var replayResponse=mvc.perform(post("/api/v1/machines/"+machine+"/state-recalculations").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("scope","PERIOD","from",historicalObserved.minusSeconds(1),"to",historicalObserved.plusSeconds(1)))))
        .andExpect(status().isAccepted()).andReturn();
    UUID replayId=UUID.fromString(json.readTree(replayResponse.getResponse().getContentAsString()).path("id").asText());
    projectionWorker.processAvailableWork();
    assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM analytics.state_recalculation_job WHERE id=?",String.class,replayId));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=(SELECT generation FROM telemetry.recalculation_work WHERE machine_id=?)",Integer.class,machine,machine));

    jdbc.update("INSERT INTO equipment.telemetry_signal(id,machine_id,code,name,value_type,signal_kind,semantic_code) "
        + "SELECT md5(? || ':' || n::text)::uuid,?, 'EXTRA_' || lpad(n::text,3,'0'), 'Extra ' || n::text, "
        + "'DOUBLE','GAUGE','ROTATIONAL_SPEED' FROM generate_series(1,501) n", machine.toString(), machine);
    mvc.perform(get(signalPath)).andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(500))
        .andExpect(jsonPath("$.truncated").value(true));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement WHERE ingest_batch_id=?",Integer.class,id));
    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      try (var statement = connection.createStatement()) {
        statement.execute("SET session_replication_role='replica'");
        try (var delete = connection.prepareStatement("DELETE FROM telemetry.raw_measurement WHERE ingest_batch_id=?")) {
          delete.setLong(1, id);
          delete.executeUpdate();
        } finally {
          statement.execute("SET session_replication_role='origin'");
        }
      }
      return null;
    });
    jdbc.update("UPDATE equipment.telemetry_signal SET stale_after_ms=1 WHERE id=?",signal);
    jdbc.queryForObject("SELECT analytics.refresh_signal_current_for_machine(?,?)", Integer.class,
        machine, Timestamp.from(observed.plusSeconds(1)));
    mvc.perform(get(signalPath).param("signalId", signal.toString())).andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].lastReceivedValue").value(120))
        .andExpect(jsonPath("$.items[0].lastUsableValue").value(120))
        .andExpect(jsonPath("$.items[0].freshness").value("STALE"));
  }

  private UUID create(String path,Map<String,Object> body)throws Exception {
    String response=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(json.readTree(response).path("id").asText());
  }
}
