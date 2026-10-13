package ru.fkit.bi.analytics.machine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentConfigurationApiTest {
  @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("fkit.auth.jwt-secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");}
  @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;

  @BeforeEach void clean(){jdbc.update("delete from analytics.machine_state_rule_runtime");jdbc.update("delete from analytics.machine_evaluation_deadline");jdbc.update("delete from analytics.machine_state_interval");jdbc.update("delete from analytics.machine_current_state");jdbc.update("delete from analytics.signal_current_value");jdbc.update("delete from telemetry.recalculation_work");jdbc.update("delete from telemetry.recalculation_hint");jdbc.update("delete from analytics.machine_state_config");jdbc.update("delete from telemetry.signal_usage");jdbc.update("delete from equipment.telemetry_signal");jdbc.update("delete from equipment.component");jdbc.update("delete from equipment.machine");jdbc.update("delete from equipment.production_line");jdbc.update("delete from equipment.shift");jdbc.update("delete from equipment.enterprise");}

  @Test @WithMockUser void lineMachineAndBatchAssignmentAreScopedAndAtomic() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String otherEnterprise=createEnterprise("PLANT_B");String line=createLine(enterprise,"LINE_A");String otherLine=createLine(enterprise,"LINE_B");
    String free=createMachine(enterprise,null,"M_FREE");String assigned=createMachine(enterprise,otherLine,"M_ASSIGNED");String foreign=createMachine(otherEnterprise,null,"M_FOREIGN");
    mvc.perform(post("/api/v1/lines/"+line+"/machine-assignments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("machineIds",List.of(free,assigned,foreign)))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MACHINE_ASSIGNMENT_CONFLICT")).andExpect(jsonPath("$.details.machineIds.length()").value(2));
    mvc.perform(get("/api/v1/machines/"+free)).andExpect(status().isOk()).andExpect(jsonPath("$.lineId").doesNotExist());
    mvc.perform(post("/api/v1/lines/"+line+"/machine-assignments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("machineIds",List.of(free)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].lineId").value(line)).andExpect(jsonPath("$.items[0].sortOrder").value(10));
  }

  @Test @WithMockUser void componentParentMustStayInMachineAndAcyclic() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String machine=createMachine(enterprise,null,"M_A");String otherMachine=createMachine(enterprise,null,"M_B");
    String parent=createComponent(machine,"PARENT",null);String child=createComponent(machine,"CHILD",null);String foreign=createComponent(otherMachine,"FOREIGN",null);
    mvc.perform(put("/api/v1/components/"+child+"/parent").contentType(MediaType.APPLICATION_JSON).content("{\"parentComponentId\":\""+foreign+"\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPONENT_PARENT_OWNER_CONFLICT"));
    mvc.perform(put("/api/v1/components/"+child+"/parent").contentType(MediaType.APPLICATION_JSON).content("{\"parentComponentId\":\""+parent+"\"}"))
        .andExpect(status().isOk());
    mvc.perform(put("/api/v1/components/"+parent+"/parent").contentType(MediaType.APPLICATION_JSON).content("{\"parentComponentId\":\""+child+"\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPONENT_TREE_CYCLE"));
    mvc.perform(get("/api/v1/components/"+parent)).andExpect(status().isOk()).andExpect(jsonPath("$.parentComponentId").doesNotExist());
  }

  @Test @WithMockUser void rawUseLocksSignalMeaningAndStateValidationDoesNotPersist() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String line=createLine(enterprise,"LINE_A");String machine=createMachine(enterprise,line,"M_A");String signal=createSignal(machine);
    jdbc.update("insert into telemetry.signal_usage(signal_id) values (?)",UUID.fromString(signal));
    mvc.perform(put("/api/v1/telemetry-signals/"+signal).contentType(MediaType.APPLICATION_JSON).content(signalUpdate("PRESSURE")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIGNAL_SEMANTICS_LOCKED"));
    String invalid=stateConfiguration(signal,"not-a-number");
    mvc.perform(post("/api/v1/machines/"+machine+"/state-configuration/validate").contentType(MediaType.APPLICATION_JSON).content(invalid))
        .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.errors.length()").value(1));
    mvc.perform(put("/api/v1/machines/"+machine+"/state-configuration").contentType(MediaType.APPLICATION_JSON).content(invalid))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STATE_CONFIGURATION_INVALID"));
    mvc.perform(get("/api/v1/machines/"+machine+"/state-configuration")).andExpect(status().isNotFound());
    mvc.perform(put("/api/v1/machines/"+machine+"/state-configuration").contentType(MediaType.APPLICATION_JSON).content(stateConfiguration(signal,"0")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.machineId").value(machine)).andExpect(jsonPath("$.rules.length()").value(1));
    mvc.perform(get("/api/v1/machines/"+machine+"/activation-readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
    mvc.perform(put("/api/v1/machines/"+machine+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"} "))
        .andExpect(status().isOk());
    mvc.perform(put("/api/v1/telemetry-signals/"+signal+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIGNAL_USED_BY_ACTIVE_STATE_CONFIG"));
  }

  @Test @WithMockUser void unreadyMachineCannotBeActivated() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String line=createLine(enterprise,"LINE_A");String machine=createMachine(enterprise,line,"M_A");
    mvc.perform(put("/api/v1/machines/"+machine+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACTIVATION_NOT_READY"))
        .andExpect(jsonPath("$.details.errors[0].code").value("STATE_CONFIGURATION_REQUIRED"));
    mvc.perform(get("/api/v1/machines/"+machine)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
  }

  @Test @WithMockUser void equipmentTreeGroupsAssignedAndUnassignedMachinesAndAppliesFilters() throws Exception {
    String enterprise=createEnterprise("PLANT_A");
    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enterprise.id").value(enterprise))
        .andExpect(jsonPath("$.lines.length()").value(0)).andExpect(jsonPath("$.unassignedMachines.length()").value(0));
    String line=createLine(enterprise,"LINE_A");
    String assigned=createMachine(enterprise,line,"M_ASSIGNED");String free=createMachine(enterprise,null,"M_FREE");

    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree").param("status","DISABLED"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enterprise.id").value(enterprise))
        .andExpect(jsonPath("$.enterprise.code").value("PLANT_A"))
        .andExpect(jsonPath("$.lines[0].id").value(line))
        .andExpect(jsonPath("$.lines[0].machines[0].id").value(assigned))
        .andExpect(jsonPath("$.unassignedMachines[0].id").value(free))
        .andExpect(jsonPath("$.generatedAt").isString());
    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree").param("status","DISABLED").param("search","ASSIGNED"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].machines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].machines[0].id").value(assigned))
        .andExpect(jsonPath("$.unassignedMachines.length()").value(0));
    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree").param("status","DISABLED").param("search","FREE"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(0))
        .andExpect(jsonPath("$.unassignedMachines[0].id").value(free));
    mvc.perform(get("/api/v1/enterprises/"+UUID.randomUUID()+"/equipment-tree"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree").param("status","UNKNOWN"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS_FILTER"));
    mvc.perform(get("/api/v1/enterprises/"+enterprise+"/equipment-tree").param("search","X".repeat(101)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test @WithMockUser void machineHasAtMostOneActiveProductionCounterPerMetricSemantic() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String machine=createMachine(enterprise,null,"M_A");
    String first=createProductionSignal(machine,"OUTPUT_A");
    mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON)
        .content(productionSignal("OUTPUT_B")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METRIC_SIGNAL_SEMANTIC_CONFLICT"));
    mvc.perform(put("/api/v1/telemetry-signals/"+first+"/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON)
        .content(productionSignal("OUTPUT_B"))).andExpect(status().isCreated());
  }

  @Test @WithMockUser void activeUnitsAreListedAndUnknownOrIncompleteUnitsHaveFieldErrors() throws Exception {
    mvc.perform(get("/api/v1/reference/measurement-units"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").isString())
        .andExpect(jsonPath("$[0].name").isString()).andExpect(jsonPath("$[0].quantityCode").isString());
    String enterprise=createEnterprise("PLANT_A");
    mvc.perform(post("/api/v1/enterprises/"+enterprise+"/machines").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"M_RATE\",\"name\":\"Machine\",\"nominalRate\":100,\"parameters\":[]}"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors[0].path").value("productionUnit"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED_WITH"));
    String machine=createMachine(enterprise,null,"M_A");
    mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"TEMP\",\"name\":\"Temperature\",\"valueType\":\"DOUBLE\",\"signalKind\":\"GAUGE\",\"semanticCode\":\"TEMPERATURE\",\"unitCode\":\"UNKNOWN\",\"featured\":true}"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors[0].path").value("unitCode"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("UNKNOWN_UNIT"));
  }

  @Test @WithMockUser void completeConfigurationJourneyPreservesOwnershipAndNonCascadingStatuses() throws Exception {
    String enterprise=createEnterprise("PLANT_A");
    createShift(enterprise,"DAY","08:00","16:00");
    createShift(enterprise,"NIGHT","16:00","08:00");
    String line=createLine(enterprise,"LINE_A");
    String otherLine=createLine(enterprise,"LINE_B");
    String directlyAssigned=createMachine(enterprise,line,"M_DIRECT");
    String initiallyFree=createMachine(enterprise,null,"M_FREE");

    mvc.perform(post("/api/v1/lines/"+line+"/machine-assignments").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("machineIds",List.of(initiallyFree)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].lineId").value(line));
    mvc.perform(post("/api/v1/lines/"+otherLine+"/machine-assignments").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("machineIds",List.of(initiallyFree)))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MACHINE_ASSIGNMENT_CONFLICT"));
    mvc.perform(get("/api/v1/machines/"+initiallyFree))
        .andExpect(status().isOk()).andExpect(jsonPath("$.lineId").value(line));

    String component=createComponent(directlyAssigned,"DRIVE",null);
    String signal=createSignal(directlyAssigned,component,"SPEED","ROTATIONAL_SPEED");
    mvc.perform(get("/api/v1/machines/"+directlyAssigned+"/components"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].machineId").value(directlyAssigned));
    mvc.perform(get("/api/v1/machines/"+directlyAssigned+"/telemetry-signals"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].componentId").value(component));

    mvc.perform(put("/api/v1/machines/"+directlyAssigned+"/state-configuration")
            .contentType(MediaType.APPLICATION_JSON).content(stateConfiguration(signal,"100")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/machines/"+directlyAssigned+"/activation-readiness"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
    mvc.perform(put("/api/v1/machines/"+directlyAssigned+"/status")
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

    mvc.perform(put("/api/v1/lines/"+line+"/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    mvc.perform(get("/api/v1/machines/"+directlyAssigned))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    mvc.perform(get("/api/v1/machines/"+directlyAssigned+"/activation-readiness"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.errors[0].code").value("LINE_DISABLED"));
    mvc.perform(get("/api/v1/components/"+component)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/telemetry-signals/"+signal)).andExpect(status().isOk());
  }

  @Test @WithMockUser void invalidSignalOwnershipAndTypeLeaveNoPartialRows() throws Exception {
    String enterprise=createEnterprise("PLANT_A");
    String machine=createMachine(enterprise,null,"M_A");
    String foreignMachine=createMachine(enterprise,null,"M_B");
    String foreignComponent=createComponent(foreignMachine,"FOREIGN",null);

    mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals")
            .contentType(MediaType.APPLICATION_JSON).content(signalCreate(foreignComponent,"TEMP","DOUBLE","GAUGE","TEMPERATURE",null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIGNAL_COMPONENT_OWNER_CONFLICT"));
    mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals")
            .contentType(MediaType.APPLICATION_JSON).content(signalCreate(null,"FLAG","BOOLEAN","COUNTER","CUSTOM","CUSTOM_FLAG")))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SIGNAL_TYPE_KIND_MISMATCH"));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from equipment.telemetry_signal where machine_id=?",Integer.class,UUID.fromString(machine))).isZero();
  }

  @Test @WithMockUser void rejectedStateConfigurationReplacementKeepsLastCommittedDocument() throws Exception {
    String enterprise=createEnterprise("PLANT_A");String line=createLine(enterprise,"LINE_A");
    String machine=createMachine(enterprise,line,"M_A");String signal=createSignal(machine);
    String valid=stateConfiguration(signal,"0");
    mvc.perform(put("/api/v1/machines/"+machine+"/state-configuration")
            .contentType(MediaType.APPLICATION_JSON).content(valid))
        .andExpect(status().isOk());
    JsonNode before=json.readTree(mvc.perform(get("/api/v1/machines/"+machine+"/state-configuration"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

    mvc.perform(put("/api/v1/machines/"+machine+"/state-configuration")
            .contentType(MediaType.APPLICATION_JSON).content(stateConfiguration(signal,"not-a-number")))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STATE_CONFIGURATION_INVALID"))
        .andExpect(jsonPath("$.details.errors[0].path").value("rules[0].enterCondition.value"));
    JsonNode after=json.readTree(mvc.perform(get("/api/v1/machines/"+machine+"/state-configuration"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

    org.assertj.core.api.Assertions.assertThat(after.get("defaultState")).isEqualTo(before.get("defaultState"));
    org.assertj.core.api.Assertions.assertThat(after.get("offlineAfterMs")).isEqualTo(before.get("offlineAfterMs"));
    org.assertj.core.api.Assertions.assertThat(after.get("rules")).isEqualTo(before.get("rules"));
  }

  private String createEnterprise(String code)throws Exception{return id(mvc.perform(post("/api/v1/enterprises").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+code+"\",\"name\":\"Plant\",\"timezone\":\"Europe/Moscow\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createLine(String enterprise,String code)throws Exception{return id(mvc.perform(post("/api/v1/enterprises/"+enterprise+"/lines").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+code+"\",\"name\":\"Line\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createShift(String enterprise,String code,String start,String end)throws Exception{return id(mvc.perform(post("/api/v1/enterprises/"+enterprise+"/shifts").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+code+"\",\"name\":\""+code+"\",\"startLocalTime\":\""+start+"\",\"endLocalTime\":\""+end+"\",\"validFrom\":\"2026-01-01\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createMachine(String enterprise,String line,String code)throws Exception{Map<String,Object> body=new LinkedHashMap<>();body.put("code",code);body.put("name",code);body.put("lineId",line);body.put("parameters",List.of());return id(mvc.perform(post("/api/v1/enterprises/"+enterprise+"/machines").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createComponent(String machine,String code,String parent)throws Exception{return id(mvc.perform(post("/api/v1/machines/"+machine+"/components").contentType(MediaType.APPLICATION_JSON).content("{\"parentComponentId\":"+(parent==null?"null":"\""+parent+"\"")+",\"code\":\""+code+"\",\"name\":\""+code+"\",\"kind\":\"OTHER\",\"sortOrder\":0,\"parameters\":[]}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createSignal(String machine)throws Exception{return id(mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TEMP\",\"name\":\"Temperature\",\"valueType\":\"DOUBLE\",\"signalKind\":\"GAUGE\",\"semanticCode\":\"TEMPERATURE\",\"staleAfterMs\":60000,\"featured\":true}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String createSignal(String machine,String component,String code,String semantic)throws Exception{return id(mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON).content(signalCreate(component,code,"DOUBLE","GAUGE",semantic,null))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String signalCreate(String component,String code,String valueType,String kind,String semantic,String customSemantic)throws Exception{Map<String,Object> body=new LinkedHashMap<>();body.put("componentId",component);body.put("code",code);body.put("name",code);body.put("valueType",valueType);body.put("signalKind",kind);body.put("semanticCode",semantic);body.put("customSemanticCode",customSemantic);body.put("staleAfterMs",60000);body.put("featured",true);return json.writeValueAsString(body);}
  private String createProductionSignal(String machine,String code)throws Exception{return id(mvc.perform(post("/api/v1/machines/"+machine+"/telemetry-signals").contentType(MediaType.APPLICATION_JSON).content(productionSignal(code))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
  private String productionSignal(String code){return "{\"code\":\""+code+"\",\"name\":\"Output\",\"valueType\":\"LONG\",\"signalKind\":\"COUNTER\",\"semanticCode\":\"PRODUCTION_COUNT\",\"unitCode\":\"PCS\",\"staleAfterMs\":60000}";}
  private String signalUpdate(String semantic){return "{\"name\":\"Temperature\",\"valueType\":\"DOUBLE\",\"signalKind\":\"GAUGE\",\"semanticCode\":\""+semantic+"\",\"staleAfterMs\":60000,\"featured\":true}";}
  private String stateConfiguration(String signal,String value){return "{\"defaultState\":\"IDLE\",\"offlineAfterMs\":60000,\"rules\":[{\"name\":\"Run\",\"priority\":10,\"targetState\":\"RUN\",\"enterCondition\":{\"type\":\"COMPARE\",\"signalId\":\""+signal+"\",\"operator\":\"GT\",\"value\":"+(value.equals("not-a-number")?"\"x\"":value)+"},\"activationDelayMs\":0,\"releaseDelayMs\":0}]}";}
  private String id(String body)throws Exception{JsonNode node=json.readTree(body);return node.get("id").asText();}
}
