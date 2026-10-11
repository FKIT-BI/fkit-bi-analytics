package ru.fkit.bi.analytics.incidents;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

@Testcontainers
@SpringBootTest(properties={"telemetry.handoff.poll-ms=3600000","telemetry.projection.poll-ms=3600000","telemetry.deadline.poll-ms=3600000"})
@AutoConfigureMockMvc
class ManualLossAttributionApiTest {
  @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("fkit.auth.jwt-secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");}
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;

  @Test @WithMockUser void normalizedReasonUniquenessAndUsedCategoryImmutability() throws Exception {
    Scenario s=setupEnterpriseAndMachine();UUID enterprise=s.enterprise();
    UUID reason=createReason(enterprise,"MATERIAL_SHORT","Material shortage","MATERIAL");
    mvc.perform(post("/api/v1/enterprises/"+enterprise+"/loss-reasons").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"MATERIAL_SHORT_2\",\"name\":\"  material shortage \",\"category\":\"MATERIAL\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOSS_REASON_DUPLICATE"));
    // Mark historical use directly; API-created incidents below exercise this path end to end.
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),from=now.minusSeconds(30),to=now.minusSeconds(10);
    seedReadyState(s.machine(),from,now.minusSeconds(1));
    String incident=mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","catalog-use-key-001")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,null,reason,null)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    mvc.perform(put("/api/v1/loss-reasons/"+reason).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Material shortage\",\"category\":\"PROCESS\",\"description\":null,\"color\":null}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOSS_REASON_CATEGORY_IMMUTABLE"));
    mvc.perform(put("/api/v1/loss-reasons/"+reason+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    mvc.perform(get("/api/v1/incidents/"+json.readTree(incident).get("id").asText()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.lossReason.status").value("DISABLED"));
  }

  @Test @WithMockUser void incidentRetryOverlapDimensionsAndCancellationAreDurable() throws Exception {
    Scenario s=setupEnterpriseAndMachine();UUID reason=createReason(s.enterprise(),"EQUIPMENT_STOP","Equipment stop","EQUIPMENT");
    UUID otherReason=createReason(s.enterprise(),"PROCESS_STOP","Process stop","PROCESS");
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),from=now.minusSeconds(45),to=now.minusSeconds(12);
    seedReadyState(s.machine(),from,now.minusSeconds(1));String body=incident(from,to,null,reason,"note");
    String first=mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","lost-response-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    JsonNode created=json.readTree(first);String id=created.get("id").asText();
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","lost-response-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
    Scenario otherMachine=setupEnterpriseAndMachine();seedReadyState(otherMachine.machine(),from,now.minusSeconds(1));
    mvc.perform(post("/api/v1/machines/"+otherMachine.machine()+"/incidents").header("Idempotency-Key","lost-response-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","lost-response-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,null,otherReason,"different")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","overlap-reason-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,null,otherReason,null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_REASON_OVERLAP"));
    String stateIncident=mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","overlap-state-key-1")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,"SETUP",null,null)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String stateId=json.readTree(stateIncident).get("id").asText();
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","overlap-state-key-2")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,"PLANNED_STOP",null,null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_STATE_OVERLAP"));
    mvc.perform(delete("/api/v1/incidents/"+id)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/incidents/"+stateId)).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/incidents/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    String ongoing=mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","ongoing-close-key-01")
        .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\""+from+"\",\"to\":null,\"stateOverride\":\"SETUP\",\"lossReasonId\":null,\"comment\":\"ongoing\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.to").isEmpty()).andReturn().getResponse().getContentAsString();
    String ongoingId=json.readTree(ongoing).get("id").asText();Instant closedAt=now.minusSeconds(3);
    mvc.perform(put("/api/v1/incidents/"+ongoingId+"/close").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\""+closedAt+"\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.to").value(closedAt.toString()));
    mvc.perform(put("/api/v1/incidents/"+ongoingId).contentType(MediaType.APPLICATION_JSON).content(incident(from,closedAt,"SETUP",null,"updated")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.comment").value("updated"));
    mvc.perform(put("/api/v1/incidents/"+ongoingId+"/close").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\""+closedAt+"\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.to").value(closedAt.toString()));
    Integer queued=jdbc.queryForObject("SELECT count(*) FROM telemetry.recalculation_work WHERE machine_id=? AND status<>'IDLE'",Integer.class,s.machine());
    org.junit.jupiter.api.Assertions.assertTrue(queued!=null&&queued>0);
  }

  @Test @WithMockUser void reasonOnlyWithoutEligibleLossAndForeignTenantReasonAreRejected() throws Exception {
    Scenario s=setupEnterpriseAndMachine();UUID reason=createReason(s.enterprise(),"IDLE_REASON","Idle","PROCESS");
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),from=now.minusSeconds(30),to=now.minusSeconds(10);
    seedReadyState(s.machine(),from,now.minusSeconds(1));
    jdbc.update("UPDATE analytics.machine_current_state SET state='RUN' WHERE machine_id=?",s.machine());
    jdbc.update("UPDATE analytics.machine_state_interval SET state='RUN' WHERE machine_id=?",s.machine());
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","no-loss-interval-key")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,null,reason,null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_HAS_NO_LOSS_INTERVAL"));

    Instant dataThrough=now.minusSeconds(1),afterDataThrough=dataThrough.plusMillis(100);
    jdbc.update("UPDATE analytics.machine_state_interval SET state='IDLE',effective_to=NULL WHERE machine_id=?",s.machine());
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","ongoing-after-through")
        .contentType(MediaType.APPLICATION_JSON).content(incident(afterDataThrough,null,null,reason,null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_HAS_NO_LOSS_INTERVAL"));

    Scenario foreign=setupEnterpriseAndMachine();UUID foreignReason=createReason(foreign.enterprise(),"FOREIGN_REASON","Foreign","PROCESS");
    mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","foreign-reason-key-01")
        .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,"SETUP",foreignReason,null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOSS_REASON_NOT_ASSIGNABLE"));
  }

  @Test @WithMockUser void incidentListCursorPagesAreStableAndMalformedCursorIsRejected() throws Exception {
    Scenario s=setupEnterpriseAndMachine();
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),through=now.minusSeconds(1),base=now.minusSeconds(40);
    seedReadyState(s.machine(),base,through);
    for(int i=0;i<3;i++){
      Instant from=base.plusSeconds(i*10L),to=from.plusSeconds(5);
      mvc.perform(post("/api/v1/machines/"+s.machine()+"/incidents").header("Idempotency-Key","pagination-key-00"+i)
          .contentType(MediaType.APPLICATION_JSON).content(incident(from,to,"SETUP",null,"page "+i))).andExpect(status().isCreated());
    }
    JsonNode first=json.readTree(mvc.perform(get("/api/v1/machines/"+s.machine()+"/incidents").param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.cursor.hasMore").value(true)).andReturn().getResponse().getContentAsString());
    String firstId=first.path("items").get(0).path("id").asText(),cursor=first.path("cursor").path("nextCursor").asText();
    JsonNode second=json.readTree(mvc.perform(get("/api/v1/machines/"+s.machine()+"/incidents").param("limit","1").param("cursor",cursor))
        .andExpect(status().isOk()).andExpect(jsonPath("$.cursor.hasMore").value(true)).andReturn().getResponse().getContentAsString());
    String secondId=second.path("items").get(0).path("id").asText();
    org.junit.jupiter.api.Assertions.assertNotEquals(firstId,secondId,"cursor pages must not repeat an incident");
    mvc.perform(get("/api/v1/machines/"+s.machine()+"/incidents").param("cursor","not-a-cursor"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
  }

  private UUID createReason(UUID enterprise,String code,String name,String category)throws Exception{String r=mvc.perform(post("/api/v1/enterprises/"+enterprise+"/loss-reasons").contentType(MediaType.APPLICATION_JSON)
      .content("{\"code\":\""+code+"\",\"name\":\""+name+"\",\"category\":\""+category+"\"}"))
      .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(r).get("id").asText());}
  private Scenario setupEnterpriseAndMachine()throws Exception{String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
    UUID enterprise=postId("/api/v1/enterprises","{\"code\":\"PLANT_"+suffix+"\",\"name\":\"Plant\",\"timezone\":\"UTC\"}");
    UUID line=postId("/api/v1/enterprises/"+enterprise+"/lines","{\"code\":\"LINE_"+suffix+"\",\"name\":\"Line\"}");
    UUID machine=postId("/api/v1/enterprises/"+enterprise+"/machines","{\"code\":\"M_"+suffix+"\",\"name\":\"Machine\",\"lineId\":\""+line+"\",\"parameters\":[]}");return new Scenario(enterprise,machine);}
  private UUID postId(String path,String body)throws Exception{String r=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(r).get("id").asText());}
  private void seedReadyState(UUID machine,Instant from,Instant through){jdbc.update("UPDATE equipment.machine SET coverage_from=? WHERE id=?",Timestamp.from(from),machine);
    jdbc.update("INSERT INTO analytics.machine_current_state(machine_id,coverage_from,state,state_since,evaluation_quality,data_through,calculated_at,recalculating) VALUES(?,?,'IDLE',?,'COMPLETE',?,clock_timestamp(),false)",machine,Timestamp.from(from),Timestamp.from(from),Timestamp.from(through));
    jdbc.update("INSERT INTO analytics.machine_state_interval(machine_id,effective_from,effective_to,state,detected_at,calculated_at) VALUES(?,?,?,'IDLE',clock_timestamp(),clock_timestamp())",machine,Timestamp.from(from),Timestamp.from(through));}
  private String incident(Instant from,Instant to,String state,UUID reason,String comment){return "{\"from\":\""+from+"\",\"to\":"+(to==null?"null":"\""+to+"\"")+",\"stateOverride\":"+(state==null?"null":"\""+state+"\"")+",\"lossReasonId\":"+(reason==null?"null":"\""+reason+"\"")+",\"comment\":"+(comment==null?"null":"\""+comment+"\"")+"}";}
  private record Scenario(UUID enterprise,UUID machine){}
}
