package ru.fkit.bi.analytics.telemetry;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.*;

@RestController
public class OperationsReadController {
  private static final List<String> STATES=List.of("RUN","IDLE","DOWN","SETUP","PLANNED_STOP","OFFLINE");
  private final JdbcTemplate jdbc;
  private final CurrentTelemetryRepository telemetry;
  private final ObjectMapper json;

  public OperationsReadController(JdbcTemplate jdbc,CurrentTelemetryRepository telemetry,ObjectMapper json){this.jdbc=jdbc;this.telemetry=telemetry;this.json=json;}

  @GetMapping("/api/v1/machines/{machineId}/operations/current")
  public Map<String,Object> current(@PathVariable UUID machineId){
    Map<String,Object> machine=machine(machineId);if(machine==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Machine not found");
    UUID lineId=(UUID)machine.get("lineId");Map<String,Object> line=lineId==null?null:line(lineId);
    List<SignalValue> signals=telemetry.read(machineId,null,true,false).items();
    Map<String,Object> state=currentState(machineId);
    CurrentTelemetryModels.Currency currency=currency(machineId);
    Map<String,Object> result=new LinkedHashMap<>();result.put("machine",machine);result.put("line",line);result.put("currentState",state);result.put("featuredSignals",signals);result.put("currency",currency);return result;
  }

  @GetMapping("/api/v1/enterprises/{enterpriseId}/operations/overview")
  public Map<String,Object> overview(@PathVariable UUID enterpriseId,@RequestParam(required=false) UUID lineId,
      @RequestParam(defaultValue="false") boolean includeDisabled){
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.enterprise WHERE id=?)",Boolean.class,enterpriseId)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Enterprise not found");
    String sql="SELECT m.id,m.enterprise_id,m.line_id,m.code,m.name,m.status,m.inventory_no,m.parameters,m.created_at,m.updated_at,m.machine_type,m.manufacturer,m.model,m.commissioning_year,m.nominal_rate,m.production_unit_code,m.sort_order,m.coverage_from, "
        +"c.state calculated_state,c.state_since,c.matched_rule_id,c.evaluation_quality,c.last_telemetry_observed_at,c.data_through,c.calculated_at, "
        +"e.effective_state,e.effective_from,e.state_source,e.state_incident_id,e.reason_incident_id,e.loss_reason_id,e.calculated_state effective_calculated_state, "
        +"(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=m.id AND h.consumed_at IS NULL) OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=m.id AND w.status<>'IDLE')) stale, "
        +"EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=m.id AND w.status='RUNNING') recalculating "
        +"FROM equipment.machine m LEFT JOIN analytics.machine_current_state c ON c.machine_id=m.id "
        +"LEFT JOIN analytics.machine_effective_state_interval e ON e.machine_id=m.id AND e.effective_to IS NULL WHERE m.enterprise_id=? ";
    List<Object> args=new ArrayList<>();args.add(enterpriseId);
    if(lineId!=null){sql+="AND m.line_id=? ";args.add(lineId);}
    if(!includeDisabled)sql+="AND m.status='ACTIVE' ";
    sql+="ORDER BY m.line_id NULLS LAST,m.sort_order NULLS LAST,m.code,m.id LIMIT 1001";
    List<OverviewMachine> machines=jdbc.query(sql,(rs,i)->overviewMachine(rs),args.toArray());
    if(machines.size()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Overview exceeds the 1000-machine bound");
    Map<UUID,List<OverviewMachine>> byLine=new LinkedHashMap<>();List<Map<String,Object>> unassigned=new ArrayList<>();
    for(OverviewMachine item:machines){if(item.lineId()==null)unassigned.add(item.card());else byLine.computeIfAbsent(item.lineId(),ignored->new ArrayList<>()).add(item);}
    List<Map<String,Object>> lines=new ArrayList<>();for(var entry:byLine.entrySet()){
      Map<String,Object> line=line(entry.getKey());if(line==null)continue;List<OverviewMachine> group=entry.getValue();
      Map<String,Object> row=new LinkedHashMap<>();row.put("lineId",entry.getKey());row.put("lineCode",line.get("code"));row.put("lineName",line.get("name"));row.put("stateCounts",counts(group));row.put("machines",group.stream().map(OverviewMachine::card).toList());lines.add(row);
    }
    Map<String,Object> result=new LinkedHashMap<>();result.put("enterpriseId",enterpriseId);result.put("stateCounts",counts(machines));
    result.put("staleMachineCount",machines.stream().filter(m->m.stale()).count());result.put("recalculatingMachineCount",machines.stream().filter(m->m.recalculating()).count());
    result.put("lines",lines);result.put("generatedAt",Instant.now());return result;
  }

  private Map<String,Object> machine(UUID id){return jdbc.query("SELECT id,enterprise_id,line_id,code,name,status,inventory_no,parameters,created_at,updated_at,machine_type,manufacturer,model,commissioning_year,nominal_rate,production_unit_code,sort_order,coverage_from FROM equipment.machine WHERE id=?",rs->{if(!rs.next())return null;return mapMachine(rs);},id);}
  private Map<String,Object> line(UUID id){return jdbc.query("SELECT id,enterprise_id,code,name,sort_order,status,created_at,updated_at FROM equipment.production_line WHERE id=?",rs->{if(!rs.next())return null;Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getObject("id",UUID.class));m.put("enterpriseId",rs.getObject("enterprise_id",UUID.class));m.put("code",rs.getString("code"));m.put("name",rs.getString("name"));m.put("sortOrder",rs.getInt("sort_order"));m.put("status",rs.getString("status"));m.put("createdAt",instant(rs,"created_at"));m.put("updatedAt",instant(rs,"updated_at"));return m;},id);}
  private Map<String,Object> mapMachine(ResultSet rs)throws SQLException{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getObject("id",UUID.class));m.put("enterpriseId",rs.getObject("enterprise_id",UUID.class));m.put("lineId",rs.getObject("line_id",UUID.class));m.put("code",rs.getString("code"));m.put("name",rs.getString("name"));m.put("machineType",rs.getString("machine_type"));m.put("inventoryNo",rs.getString("inventory_no"));m.put("manufacturer",rs.getString("manufacturer"));m.put("model",rs.getString("model"));m.put("commissioningYear",rs.getObject("commissioning_year"));m.put("nominalRate",rs.getBigDecimal("nominal_rate"));m.put("productionUnit",rs.getString("production_unit_code"));m.put("sortOrder",rs.getObject("sort_order"));m.put("status",rs.getString("status"));m.put("coverageFrom",instant(rs,"coverage_from"));try{m.put("parameters",json.readTree(rs.getString("parameters")));}catch(Exception ex){m.put("parameters",List.of());}m.put("createdAt",instant(rs,"created_at"));m.put("updatedAt",instant(rs,"updated_at"));return m;}
  private Map<String,Object> currentState(UUID machineId){return jdbc.query("SELECT c.state,c.state_since,c.matched_rule_id,c.evaluation_quality,c.last_telemetry_observed_at,c.data_through,c.calculated_at,e.effective_state,e.effective_from,e.state_source,e.state_incident_id,e.reason_incident_id,e.loss_reason_id,e.calculated_state effective_calculated_state, "
      +"(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=c.machine_id AND h.consumed_at IS NULL) OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status<>'IDLE')) stale,EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status='RUNNING') recalculating "
      +"FROM analytics.machine_current_state c LEFT JOIN analytics.machine_effective_state_interval e ON e.machine_id=c.machine_id AND e.effective_to IS NULL WHERE c.machine_id=?",rs->{if(!rs.next())return null;String calculated=rs.getString("state"),effective=rs.getString("effective_state");Map<String,Object> m=new LinkedHashMap<>();m.put("effectiveState",effective==null?calculated:effective);m.put("calculatedState",calculated);m.put("stateSource",effective==null?"CALCULATED":rs.getString("state_source"));m.put("stateSince",instant(rs,"effective_from")==null?instant(rs,"state_since"):instant(rs,"effective_from"));m.put("matchedRuleId",rs.getObject("matched_rule_id",UUID.class));m.put("stateIncidentId",rs.getObject("state_incident_id",UUID.class));m.put("reasonIncidentId",rs.getObject("reason_incident_id",UUID.class));m.put("lossReason",reasonSummary(rs.getObject("loss_reason_id",UUID.class)));m.put("evaluationQuality",rs.getString("evaluation_quality"));m.put("lastTelemetryObservedAt",instant(rs,"last_telemetry_observed_at"));m.put("underlyingOffline","OFFLINE".equals(rs.getString("effective_calculated_state")==null?calculated:rs.getString("effective_calculated_state")));m.put("dataThrough",instant(rs,"data_through"));m.put("calculatedAt",instant(rs,"calculated_at"));m.put("stale",rs.getBoolean("stale"));m.put("recalculating",rs.getBoolean("recalculating"));return m;},machineId);}
  private CurrentTelemetryModels.Currency currency(UUID machineId){return jdbc.query("SELECT c.data_through,c.calculated_at,(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=c.machine_id AND h.consumed_at IS NULL) OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status<>'IDLE')) stale,EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status='RUNNING') recalculating FROM analytics.machine_current_state c WHERE c.machine_id=?",rs->rs.next()?new CurrentTelemetryModels.Currency(instant(rs,"data_through"),instant(rs,"calculated_at"),rs.getBoolean("stale"),rs.getBoolean("recalculating")):new CurrentTelemetryModels.Currency(null,null,false,false),machineId);}
  private OverviewMachine overviewMachine(ResultSet rs)throws SQLException{Map<String,Object> card=new LinkedHashMap<>();UUID id=rs.getObject("id",UUID.class);card.put("machineId",id);card.put("machineCode",rs.getString("code"));card.put("machineName",rs.getString("name"));card.put("machineStatus",rs.getString("status"));card.put("inventoryNo",rs.getString("inventory_no"));card.put("currentState",currentState(rs));card.put("dataThrough",instant(rs,"data_through"));card.put("calculatedAt",instant(rs,"calculated_at"));boolean stale=rs.getBoolean("stale"),recalc=rs.getBoolean("recalculating");card.put("stale",stale);card.put("recalculating",recalc);String state=rs.getString("effective_state");if(state==null)state=rs.getString("calculated_state");return new OverviewMachine(id,rs.getObject("line_id",UUID.class),state,stale,recalc,card);}
  private Map<String,Object> currentState(ResultSet rs)throws SQLException{String calculated=rs.getString("calculated_state");if(calculated==null)return null;String effective=rs.getString("effective_state");Map<String,Object> state=new LinkedHashMap<>();state.put("effectiveState",effective==null?calculated:effective);state.put("calculatedState",calculated);state.put("stateSource",effective==null?"CALCULATED":rs.getString("state_source"));state.put("stateSince",instant(rs,"effective_from")==null?instant(rs,"state_since"):instant(rs,"effective_from"));state.put("matchedRuleId",rs.getObject("matched_rule_id",UUID.class));state.put("stateIncidentId",rs.getObject("state_incident_id",UUID.class));state.put("reasonIncidentId",rs.getObject("reason_incident_id",UUID.class));state.put("lossReason",reasonSummary(rs.getObject("loss_reason_id",UUID.class)));state.put("evaluationQuality",rs.getString("evaluation_quality"));state.put("lastTelemetryObservedAt",instant(rs,"last_telemetry_observed_at"));state.put("underlyingOffline","OFFLINE".equals(rs.getString("effective_calculated_state")==null?calculated:rs.getString("effective_calculated_state")));state.put("dataThrough",instant(rs,"data_through"));state.put("calculatedAt",instant(rs,"calculated_at"));state.put("stale",rs.getBoolean("stale"));state.put("recalculating",rs.getBoolean("recalculating"));return state;}
  private Map<String,Object> reasonSummary(UUID id){if(id==null)return null;return jdbc.query("SELECT id,code,name,category,color,status FROM analytics.loss_reason WHERE id=?",rs->{if(!rs.next())return null;Map<String,Object> r=new LinkedHashMap<>();r.put("id",rs.getObject("id",UUID.class));r.put("code",rs.getString("code"));r.put("name",rs.getString("name"));r.put("category",rs.getString("category"));r.put("color",rs.getString("color"));r.put("status",rs.getString("status"));return r;},id);}
  private List<Map<String,Object>> counts(List<OverviewMachine> machines){List<Map<String,Object>> values=new ArrayList<>();for(String state:STATES)values.add(Map.of("state",state,"count",machines.stream().filter(m->state.equals(m.state())).count()));return values;}
  private Instant instant(ResultSet rs,String col)throws SQLException{var value=rs.getTimestamp(col);return value==null?null:value.toInstant();}
  private Instant instant(Object value){return value instanceof java.sql.Timestamp ts?ts.toInstant():null;}
  private record OverviewMachine(UUID id,UUID lineId,String state,boolean stale,boolean recalculating,Map<String,Object> card){}
}
