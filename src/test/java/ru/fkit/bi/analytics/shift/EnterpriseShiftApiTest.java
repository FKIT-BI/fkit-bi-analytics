package ru.fkit.bi.analytics.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import ru.fkit.bi.analytics.api.BusinessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.fkit.bi.analytics.shift.ShiftModels.Create;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EnterpriseShiftApiTest {
  @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
    r.add("spring.datasource.password",postgres::getPassword);r.add("fkit.auth.jwt-secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ShiftService service;
  @BeforeEach void clean(){
    jdbc.execute("drop table if exists incident.incident");
    jdbc.execute("drop table if exists analytics.machine_metric_bucket_1m");
    jdbc.execute("drop table if exists analytics.machine_loss_bucket_1m");
    jdbc.execute("drop table if exists generator.generation_run");
    jdbc.update("delete from analytics.machine_state_config");
    jdbc.update("delete from telemetry.signal_usage");
    jdbc.update("delete from equipment.telemetry_signal");
    jdbc.update("delete from equipment.component");
    jdbc.update("delete from equipment.machine");
    jdbc.update("delete from equipment.production_line");
    jdbc.update("delete from equipment.shift");jdbc.update("delete from equipment.enterprise");
  }

  @Test void configurationApisRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/enterprises")).andExpect(status().isUnauthorized());
  }

  @Test @WithMockUser void enterpriseAndShiftArePersistedAndOverlapIsRejected() throws Exception {
    String enterprise=mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"PLANT_1\",\"name\":\"Plant\",\"timezone\":\"Europe/Moscow\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn().getResponse().getContentAsString();
    String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(enterprise).get("id").asText();
    String shift="{\"code\":\"SHIFT_A\",\"name\":\"Shift A\",\"startLocalTime\":\"08:00\",\"endLocalTime\":\"16:00\",\"validFrom\":\"2026-01-01\"}";
    mvc.perform(post("/api/v1/enterprises/"+id+"/shifts").contentType(MediaType.APPLICATION_JSON).content(shift))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.enterpriseId").value(id));
    mvc.perform(post("/api/v1/enterprises/"+id+"/shifts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"SHIFT_B\",\"name\":\"Shift B\",\"startLocalTime\":\"15:00\",\"endLocalTime\":\"17:00\",\"validFrom\":\"2026-01-01\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_SCHEDULE_CONFLICT"));
  }

  @Test @WithMockUser void enterpriseListDefaultsToActiveAndReturnsPageMetadata() throws Exception {
    createEnterprise("PLANT_A");
    String disabledId=createEnterprise("PLANT_B");
    mvc.perform(put("/api/v1/enterprises/"+disabledId+"/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/enterprises").param("page","0").param("size","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(1))
        .andExpect(jsonPath("$.page.totalElements").value(1)).andExpect(jsonPath("$.page.totalPages").value(1));

    mvc.perform(get("/api/v1/enterprises").param("status","DISABLED").param("page","0").param("size","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("DISABLED"))
        .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(1))
        .andExpect(jsonPath("$.page.totalElements").value(1)).andExpect(jsonPath("$.page.totalPages").value(1));

    mvc.perform(get("/api/v1/enterprises").param("search","%"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test @WithMockUser void invalidTimezoneAndDuplicateEnterpriseCodeAreRejected() throws Exception {
    String body="{\"code\":\"PLANT_1\",\"name\":\"Plant\",\"timezone\":\"Europe/Moscow\"}";
    mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("Europe/Moscow","Not/A_Timezone")))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
    mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTERPRISE_CODE_CONFLICT"));
  }

  @Test @WithMockUser void timezoneCannotChangeAfterTemporalDataExists() throws Exception {
    String id=createEnterprise("PLANT_1");
    createCanonicalMachineAndSignalUsage(UUID.fromString(id));
    mvc.perform(put("/api/v1/enterprises/"+id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Plant\",\"timezone\":\"Europe/London\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTERPRISE_TIMEZONE_LOCKED"));
    mvc.perform(get("/api/v1/enterprises/"+id)).andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("Europe/Moscow"));
  }

  @Test @WithMockUser void timezoneIsLockedByMachineOwnedIncidentAndAnalyticsProjection() throws Exception {
    String id=createEnterprise("PLANT_1");
    UUID enterpriseId=UUID.fromString(id);
    UUID machineId=createMachine(enterpriseId);
    jdbc.execute("create table incident.incident (machine_id uuid not null)");
    jdbc.update("insert into incident.incident(machine_id) values (?)",machineId);
    assertTimezoneLocked(id);

    jdbc.execute("drop table incident.incident");
    jdbc.execute("create table analytics.machine_metric_bucket_1m (machine_id uuid not null)");
    jdbc.update("insert into analytics.machine_metric_bucket_1m(machine_id) values (?)",machineId);
    assertTimezoneLocked(id);
  }

  @Test @WithMockUser void usedShiftBoundaryAndValidityCannotExcludeHistoricalDate() throws Exception {
    String enterpriseId=createEnterprise("PLANT_1");
    String shiftId=createShift(enterpriseId,"SHIFT_A","08:00","16:00");
    UUID machineId=createMachine(UUID.fromString(enterpriseId));
    jdbc.execute("create table analytics.machine_metric_bucket_1m (machine_id uuid not null, shift_id uuid not null, shift_date date not null)");
    jdbc.update("insert into analytics.machine_metric_bucket_1m(machine_id,shift_id,shift_date) values (?,?,?)",machineId,UUID.fromString(shiftId),java.sql.Date.valueOf("2026-01-05"));
    mvc.perform(put("/api/v1/shifts/"+shiftId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Shift A\",\"startLocalTime\":\"09:00\",\"endLocalTime\":\"17:00\",\"validFrom\":\"2026-01-01\",\"validTo\":null,\"sortOrder\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_SCHEDULE_LOCKED"));
    mvc.perform(put("/api/v1/shifts/"+shiftId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Shift A\",\"startLocalTime\":\"08:00\",\"endLocalTime\":\"16:00\",\"validFrom\":\"2026-01-01\",\"validTo\":\"2026-01-05\",\"sortOrder\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_SCHEDULE_LOCKED"));
  }

  @Test @WithMockUser void disabledShiftsAreHiddenByDefaultAndPaginationIsBounded() throws Exception {
    String enterpriseId=createEnterprise("PLANT_1");
    String shiftId=createShift(enterpriseId,"SHIFT_A","08:00","16:00");
    mvc.perform(put("/api/v1/shifts/"+shiftId+"/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    mvc.perform(get("/api/v1/enterprises/"+enterpriseId+"/shifts"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(get("/api/v1/enterprises/"+enterpriseId+"/shifts").param("status","DISABLED"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    mvc.perform(get("/api/v1/shifts/"+shiftId))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"))
        .andExpect(jsonPath("$.id").value(shiftId));
    mvc.perform(get("/api/v1/enterprises").param("size","201"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
  }

  @Test @WithMockUser void concurrentOverlappingSchedulesPersistOnlyOne() throws Exception {
    String enterpriseId=createEnterprise("PLANT_1");
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var results=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for(String code:new String[]{"SHIFT_A","SHIFT_B"}) results.add(pool.submit(()->{
          start.await();
          try {
            service.create(UUID.fromString(enterpriseId),new Create(code,code,"08:00","16:00",
                java.time.LocalDate.parse("2026-01-01"),null,0));
            return true;
          } catch (BusinessException conflict) {
            if(conflict.status()!=409) throw conflict;
            return false;
          }
      }));
      start.countDown();
      long successful=0;
      for(var result:results) if(result.get(20,TimeUnit.SECONDS)) successful++;
      org.assertj.core.api.Assertions.assertThat(successful).isEqualTo(1);
    }
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from equipment.shift where enterprise_id=?",Integer.class,UUID.fromString(enterpriseId))).isEqualTo(1);
  }

  private String createEnterprise(String code) throws Exception {
    MvcResult result=mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\""+code+"\",\"name\":\"Plant\",\"timezone\":\"Europe/Moscow\"}"))
        .andExpect(status().isCreated()).andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private UUID createMachine(UUID enterpriseId) {
    UUID machineId=UUID.randomUUID();
    jdbc.update("insert into equipment.machine(id,enterprise_id,code,name) values (?,?,?,?)",machineId,enterpriseId,"TEST_"+machineId.toString().replace("-","").substring(0,12).toUpperCase(),"Test machine");
    return machineId;
  }

  private void createCanonicalMachineAndSignalUsage(UUID enterpriseId) {
    UUID machineId=createMachine(enterpriseId);
    UUID signalId=UUID.randomUUID();
    jdbc.update("insert into equipment.telemetry_signal(id,machine_id,code,name,value_type,signal_kind,semantic_code,featured) values (?,?,?,?,?,?,?,?)",signalId,machineId,"TEST_SIGNAL","Test signal","DOUBLE","GAUGE","TEMPERATURE",false);
    jdbc.update("insert into telemetry.signal_usage(signal_id) values (?)",signalId);
  }

  private void assertTimezoneLocked(String id) throws Exception {
    mvc.perform(put("/api/v1/enterprises/"+id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Plant\",\"timezone\":\"Europe/London\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTERPRISE_TIMEZONE_LOCKED"));
  }

  private String createShift(String enterpriseId,String code,String start,String end) throws Exception {
    MvcResult result=mvc.perform(post("/api/v1/enterprises/"+enterpriseId+"/shifts").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\""+code+"\",\"name\":\"Shift A\",\"startLocalTime\":\""+start+"\",\"endLocalTime\":\""+end+"\",\"validFrom\":\"2026-01-01\"}"))
        .andExpect(status().isCreated()).andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asText();
  }
}
