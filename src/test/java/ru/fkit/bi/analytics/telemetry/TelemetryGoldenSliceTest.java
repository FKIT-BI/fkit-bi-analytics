package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
  @Autowired ru.fkit.bi.analytics.aggregation.MinuteProjectionService minuteProjections;
  @Autowired PlatformTransactionManager transactionManager;

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
    Instant requestedFuture=Instant.now().plusSeconds(3600);
    mvc.perform(get("/api/v1/analytics/metrics/timeseries").param("scope","MACHINE").param("scopeId",machine.toString())
        .param("from",historyBucket.toString()).param("to",requestedFuture.toString()).param("resolution","5m"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.requestedTo").value(requestedFuture.toString()))
        .andExpect(jsonPath("$.metadata.partial").value(true));
    mvc.perform(get("/api/v1/analytics/summary").param("scope","MACHINE").param("scopeId",machine.toString())
        .param("from",historyBucket.toString()).param("to",requestedFuture.toString()).param("compare","PREVIOUS_PERIOD"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.current.metadata.requestedTo").value(requestedFuture.toString()))
        .andExpect(jsonPath("$.current.metadata.partial").value(true)).andExpect(jsonPath("$.previous").exists());
    mvc.perform(get("/api/v1/analytics/losses/pareto").param("scope","MACHINE").param("scopeId",machine.toString())
        .param("from",historyBucket.toString()).param("to",requestedFuture.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.partial").value(true));
    mvc.perform(get("/api/v1/analytics/summary").param("scope","MACHINE").param("scopeId",machine.toString())
        .param("from",requestedFuture.toString()).param("to",requestedFuture.plusSeconds(60).toString()))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].path").value("from"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_RANGE"));
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

    UUID reason=create("/api/v1/enterprises/"+enterprise+"/loss-reasons",Map.of("code","MANUAL_SETUP_"+suffix,
        "name","Manual setup","category","CHANGEOVER"));
    Instant overlayFrom=historicalObserved;
    Map<String,Object> overlayRequest=new java.util.LinkedHashMap<>();overlayRequest.put("from",overlayFrom);overlayRequest.put("to",null);
    overlayRequest.put("stateOverride","SETUP");overlayRequest.put("lossReasonId",reason);overlayRequest.put("comment","golden overlay");
    String incidentBody=json.writeValueAsString(overlayRequest);
    String incidentResponse=mvc.perform(post("/api/v1/machines/"+machine+"/incidents").header("Idempotency-Key","golden-overlay-"+suffix)
        .contentType(MediaType.APPLICATION_JSON).content(incidentBody)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    UUID incidentId=UUID.fromString(json.readTree(incidentResponse).path("id").asText());
    projectionWorker.processAvailableWork();
    var effective=jdbc.queryForMap("SELECT effective_state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id "
        +"FROM analytics.machine_effective_state_interval WHERE machine_id=? AND effective_from<=? AND COALESCE(effective_to,'infinity'::timestamptz)>?",
        machine,Timestamp.from(overlayFrom),Timestamp.from(overlayFrom));
    assertEquals("SETUP",effective.get("effective_state"));assertEquals("RUN",effective.get("calculated_state"));
    assertEquals("MANUAL",effective.get("state_source"));assertEquals(incidentId,effective.get("state_incident_id"));
    assertEquals(reason,effective.get("loss_reason_id"));assertEquals(incidentId,effective.get("reason_incident_id"));
    assertTrue(jdbc.queryForObject("SELECT setup_duration_ms>0 FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start=?",Boolean.class,machine,Timestamp.from(historyBucket)));
    assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.machine_loss_bucket_1m WHERE machine_id=? AND bucket_start=? AND loss_reason_id=?)",Boolean.class,machine,Timestamp.from(historyBucket),reason));

    mvc.perform(get("/api/v1/machines/"+machine+"/current-state")).andExpect(status().isOk())
        .andExpect(jsonPath("$.lossReason.id").value(reason.toString()))
        .andExpect(jsonPath("$.reasonIncidentId").value(incidentId.toString()));
    JsonNode overview=json.readTree(mvc.perform(get("/api/v1/enterprises/"+enterprise+"/operations/overview"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    JsonNode currentOverview=overview.path("lines").get(0).path("machines").get(0).path("currentState");
    assertEquals(reason.toString(),currentOverview.path("lossReason").path("id").asText());
    assertEquals(incidentId.toString(),currentOverview.path("reasonIncidentId").asText());

    JsonNode historical=json.readTree(mvc.perform(get("/api/v1/machines/"+machine+"/state-intervals")
        .param("from",overlayFrom.toString()).param("to",historicalObserved.plusSeconds(300).toString()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    JsonNode attributedInterval=null;
    for(JsonNode item:historical.path("items"))if(incidentId.toString().equals(item.path("reasonIncidentId").asText()))attributedInterval=item;
    assertNotNull(attributedInterval,historical.toString());
    assertEquals(reason.toString(),attributedInterval.path("lossReason").path("id").asText());

    JsonNode chronometry=json.readTree(mvc.perform(get("/api/v1/machines/"+machine+"/chronometry")
        .param("shiftDate",LocalDate.now(ZoneOffset.UTC).toString())).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    JsonNode attributedChronometry=null;
    for(JsonNode occurrence:chronometry.path("occurrences"))for(JsonNode item:occurrence.path("intervals"))
      if(incidentId.toString().equals(item.path("reasonIncidentId").asText()))attributedChronometry=item;
    assertNotNull(attributedChronometry,chronometry.toString());
    assertEquals(reason.toString(),attributedChronometry.path("lossReason").path("id").asText());

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

  @Test @WithMockUser
  void ongoingReasonTracksEligibleStatesOnlyAndReplayPublishesAsOneGeneration() throws Exception {
    String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
    UUID enterprise=create("/api/v1/enterprises",Map.of("code","ATTR_"+suffix,"name","Attribution","timezone","UTC"));
    UUID line=create("/api/v1/enterprises/"+enterprise+"/lines",Map.of("code","LINE_"+suffix,"name","Line"));
    UUID machine=create("/api/v1/enterprises/"+enterprise+"/machines",Map.of("code","M_"+suffix,"name","Machine","lineId",line,"parameters",java.util.List.of()));
    UUID reason=create("/api/v1/enterprises/"+enterprise+"/loss-reasons",Map.of("code","ONGOING_"+suffix,"name","Ongoing reason","category","PROCESS"));
    Instant base=Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(240),through=base.plusSeconds(180),nextThrough=base.plusSeconds(240);
    jdbc.update("UPDATE equipment.machine SET coverage_from=? WHERE id=?",Timestamp.from(base),machine);
    jdbc.update("INSERT INTO analytics.machine_current_state(machine_id,coverage_from,state,state_since,evaluation_quality,data_through,calculated_at,recalculating) VALUES(?,?,'OFFLINE',?,'COMPLETE',?,clock_timestamp(),false)",machine,Timestamp.from(base),Timestamp.from(base),Timestamp.from(through));
    jdbc.update("INSERT INTO analytics.machine_state_interval(machine_id,effective_from,effective_to,state,detected_at,calculated_at) VALUES(?,?,?,'IDLE',clock_timestamp(),clock_timestamp()),(?,?,?,'RUN',clock_timestamp(),clock_timestamp()),(?,?,?,'OFFLINE',clock_timestamp(),clock_timestamp())",
        machine,Timestamp.from(base),Timestamp.from(base.plusSeconds(60)),machine,Timestamp.from(base.plusSeconds(60)),Timestamp.from(base.plusSeconds(120)),machine,Timestamp.from(base.plusSeconds(120)),Timestamp.from(through));
    String incidentResponse=mvc.perform(post("/api/v1/machines/"+machine+"/incidents").header("Idempotency-Key","ongoing-reason-"+suffix)
        .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\""+base+"\",\"to\":null,\"stateOverride\":null,\"lossReasonId\":\""+reason+"\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    UUID reasonIncidentId=UUID.fromString(json.readTree(incidentResponse).path("id").asText());

    mvc.perform(get("/api/v1/machines/"+machine+"/current-state"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(true))
        .andExpect(jsonPath("$.effectiveState").value("OFFLINE"));

    long generation=701L;
    seedProjectionStates(machine,generation,base,through,new String[]{"IDLE","RUN","OFFLINE"});
    var firstWork=new ru.fkit.bi.analytics.telemetry.TelemetryWorkRepository.WorkItem(machine,base,through,generation,null,0,null);
    minuteProjections.stage(firstWork);
    TransactionTemplate tx=new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status->minuteProjections.publish(firstWork));
    var firstTimeline=jdbc.queryForList("SELECT effective_from,effective_to,effective_state,loss_reason_id,reason_incident_id FROM analytics.machine_effective_state_interval WHERE machine_id=? ORDER BY effective_from",machine);
    assertEquals(3,firstTimeline.size(),firstTimeline.toString());
    assertEquals(Timestamp.from(base),firstTimeline.get(0).get("effective_from"));
    assertEquals(Timestamp.from(base.plusSeconds(60)),firstTimeline.get(0).get("effective_to"));
    assertEquals("IDLE",firstTimeline.get(0).get("effective_state"));
    assertEquals(reason,firstTimeline.get(0).get("loss_reason_id"));
    assertEquals(reasonIncidentId,firstTimeline.get(0).get("reason_incident_id"));
    assertEquals("RUN",firstTimeline.get(1).get("effective_state"));
    assertEquals(null,firstTimeline.get(1).get("loss_reason_id"));
    assertEquals(null,firstTimeline.get(1).get("reason_incident_id"));
    assertEquals("OFFLINE",firstTimeline.get(2).get("effective_state"));
    assertEquals(null,firstTimeline.get(2).get("loss_reason_id"));
    assertEquals(null,firstTimeline.get(2).get("reason_incident_id"));
    assertEquals(60_000L,jdbc.queryForObject("SELECT sum(duration_ms) FROM analytics.machine_loss_bucket_1m WHERE machine_id=? AND loss_reason_id=?",Long.class,machine,reason));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM analytics.machine_effective_state_interval WHERE machine_id=? AND effective_state IN ('RUN','OFFLINE') AND loss_reason_id IS NOT NULL",Integer.class,machine));
    assertEquals(60_000L,jdbc.queryForObject("SELECT run_duration_ms FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start=?",Long.class,machine,Timestamp.from(base.plusSeconds(60))));

    // Simulate newly arrived coverage plus a late state replay: the former RUN minute is now IDLE.
    jdbc.update("UPDATE analytics.machine_current_state SET data_through=? WHERE machine_id=?",Timestamp.from(nextThrough),machine);
    long replayGeneration=702L;
    seedProjectionStates(machine,replayGeneration,base,nextThrough,new String[]{"IDLE","IDLE","OFFLINE","IDLE"});
    var replayWork=new ru.fkit.bi.analytics.telemetry.TelemetryWorkRepository.WorkItem(machine,base,nextThrough,replayGeneration,null,0,null);
    minuteProjections.stage(replayWork);
    Integer stagedIntervals=jdbc.queryForObject("SELECT count(*) FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?",Integer.class,machine,replayGeneration);
    assertNotNull(stagedIntervals);
    assertTrue(stagedIntervals>0,"replay must leave a durable staged generation before publication");
    // Model a worker restart after durable staging but before atomic publication.
    // Rebuilding the same generation must replace staging rows, not duplicate them.
    minuteProjections.stage(replayWork);
    assertEquals(stagedIntervals,jdbc.queryForObject("SELECT count(*) FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?",Integer.class,machine,replayGeneration));
    assertEquals(60_000L,jdbc.queryForObject("SELECT run_duration_ms FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start=?",Long.class,machine,Timestamp.from(base.plusSeconds(60))),"staged replay must not leak before publication");
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM analytics.machine_loss_bucket_1m WHERE machine_id=? AND bucket_start=? AND loss_reason_id=?",Integer.class,machine,Timestamp.from(base.plusSeconds(180)),reason));
    tx.executeWithoutResult(status->minuteProjections.publish(replayWork));
    var replayedTimeline=jdbc.queryForList("SELECT effective_from,effective_to,effective_state,loss_reason_id,reason_incident_id FROM analytics.machine_effective_state_interval WHERE machine_id=? ORDER BY effective_from",machine);
    assertEquals(4,replayedTimeline.size(),replayedTimeline.toString());
    assertEquals(Timestamp.from(base.plusSeconds(180)),replayedTimeline.get(3).get("effective_from"));
    assertEquals(Timestamp.from(nextThrough),replayedTimeline.get(3).get("effective_to"));
    assertEquals("IDLE",replayedTimeline.get(3).get("effective_state"));
    assertEquals(reason,replayedTimeline.get(3).get("loss_reason_id"));
    assertEquals(reasonIncidentId,replayedTimeline.get(3).get("reason_incident_id"));
    assertEquals(60_000L,jdbc.queryForObject("SELECT idle_duration_ms FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start=?",Long.class,machine,Timestamp.from(base.plusSeconds(60))));
    assertEquals(60_000L,jdbc.queryForObject("SELECT sum(duration_ms) FROM analytics.machine_loss_bucket_1m WHERE machine_id=? AND bucket_start=? AND loss_reason_id=?",Long.class,machine,Timestamp.from(base.plusSeconds(180)),reason));
  }

  private void seedProjectionStates(UUID machine,long generation,Instant from,Instant to,String[] states){
    jdbc.update("DELETE FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?",machine,generation);
    for(int i=0;i<states.length;i++){
      Instant start=from.plusSeconds(60L*i),end=start.plusSeconds(60);
      if(end.isAfter(to))end=to;
      jdbc.update("INSERT INTO analytics.staging_machine_state_interval(machine_id,effective_from,effective_to,state,detected_at,work_generation) VALUES(?,?,?,?,clock_timestamp(),?)",machine,Timestamp.from(start),Timestamp.from(end),states[i],generation);
    }
  }

  private UUID create(String path,Map<String,Object> body)throws Exception {
    String response=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(json.readTree(response).path("id").asText());
  }
}
