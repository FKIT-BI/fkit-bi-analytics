package ru.fkit.bi.analytics.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.shift.ShiftCalendar;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
public class HistoricalMetricsController {
  private static final Instant EPOCH = Instant.EPOCH;
  private final JdbcTemplate jdbc;

  public HistoricalMetricsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/api/v1/machines/{machineId}/chronometry")
  public Map<String,Object> chronometry(@PathVariable UUID machineId,
      @RequestParam java.time.LocalDate shiftDate, @RequestParam(required=false) UUID shiftId) {
    MachineEnterprise machine=jdbc.query("SELECT m.enterprise_id,e.timezone,c.data_through,c.calculated_at, "
        + "(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=m.id AND h.consumed_at IS NULL) OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=m.id AND w.status<>'IDLE')) stale, "
        + "EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=m.id AND w.status='RUNNING') recalculating FROM equipment.machine m "
        + "JOIN equipment.enterprise e ON e.id=m.enterprise_id LEFT JOIN analytics.machine_current_state c ON c.machine_id=m.id WHERE m.id=?",
        rs -> rs.next()?new MachineEnterprise(rs.getObject("enterprise_id",UUID.class),rs.getString("timezone"),timestamp(rs.getObject("data_through")),timestamp(rs.getObject("calculated_at")),rs.getBoolean("stale"),rs.getBoolean("recalculating")):null,machineId);
    if(machine==null)throw new BusinessException(404,"NOT_FOUND","Машина не найдена");
    java.time.ZoneId zone;
    try { zone=java.time.ZoneId.of(machine.timezone()); } catch(RuntimeException e) { throw invalid("У предприятия указан некорректный часовой пояс"); }
    List<ShiftOccurrence> definitions=jdbc.query("SELECT id,code,name,start_local_time,end_local_time,valid_from,valid_to,sort_order "
        + "FROM equipment.shift WHERE enterprise_id=? AND status='ACTIVE' AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) ORDER BY sort_order,code",
        (rs,i)->new ShiftOccurrence(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),
            rs.getTime("start_local_time").toLocalTime(),rs.getTime("end_local_time").toLocalTime(),rs.getDate("valid_from").toLocalDate(),
            rs.getDate("valid_to")==null?null:rs.getDate("valid_to").toLocalDate(),rs.getInt("sort_order")),machine.enterpriseId(),shiftDate,shiftDate);
    if(shiftId!=null)definitions=definitions.stream().filter(d->d.id().equals(shiftId)).toList();
    List<Map<String,Object>> occurrences=new ArrayList<>();int intervalCount=0;
    Map<UUID,Map<String,Object>> reasonSummaries=new HashMap<>();
    for(ShiftOccurrence d:definitions){
      if(shiftDate.isBefore(d.validFrom())||(d.validTo()!=null&&!shiftDate.isBefore(d.validTo())))continue;
      java.time.LocalDate endDate=d.end().isAfter(d.start())?shiftDate:shiftDate.plusDays(1);
      Instant start=ShiftCalendar.boundary(shiftDate.atTime(d.start()),zone),end=ShiftCalendar.boundary(endDate.atTime(d.end()),zone);
      Instant readyTo=machine.dataThrough()==null?start:(machine.dataThrough().isBefore(end)?machine.dataThrough():end);
      if(!start.isBefore(readyTo))continue;
      List<Map<String,Object>> intervals=jdbc.query("WITH timeline AS (SELECT effective_from,effective_to,effective_state state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id "
          + "FROM analytics.machine_effective_state_interval WHERE machine_id=? UNION ALL SELECT effective_from,effective_to,state,state,'CALCULATED',NULL,NULL,NULL "
          + "FROM analytics.machine_state_interval i WHERE machine_id=? AND NOT EXISTS(SELECT 1 FROM analytics.machine_effective_state_interval e WHERE e.machine_id=i.machine_id)) "
          + "SELECT effective_from,effective_to,state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id FROM timeline "
          + "WHERE effective_from<? AND COALESCE(effective_to,?)>? ORDER BY effective_from",
      (rs,i)->{Instant a=rs.getTimestamp("effective_from").toInstant(),rawTo=rs.getTimestamp("effective_to")==null?readyTo:rs.getTimestamp("effective_to").toInstant(),b=rawTo;a=a.isBefore(start)?start:a;b=b.isAfter(readyTo)?readyTo:b;if(!a.isBefore(b))return null;String effective=rs.getString("state"),calculated=rs.getString("calculated_state");UUID reason=rs.getObject("loss_reason_id",UUID.class);Map<String,Object> row=new LinkedHashMap<>();row.put("effectiveState",effective);row.put("from",a);row.put("to",b);row.put("durationSec",ChronoUnit.MILLIS.between(a,b)/1000d);row.put("openEnded",rs.getTimestamp("effective_to")==null);row.put("calculatedState",calculated);row.put("stateSource",rs.getString("state_source"));row.put("stateIncidentId",rs.getObject("state_incident_id",UUID.class));row.put("reasonIncidentId",rs.getObject("reason_incident_id",UUID.class));row.put("lossReason",reasonSummary(reason,reasonSummaries));row.put("underlyingOffline","OFFLINE".equals(calculated));return row;},machineId,machineId,Timestamp.from(readyTo),Timestamp.from(readyTo),Timestamp.from(start)).stream().filter(Objects::nonNull).toList();
      intervalCount+=intervals.size();if(intervalCount>10_000)throw new BusinessException(413,"CHRONOMETRY_TOO_LARGE","Хронометраж превышает 10000 интервалов");
      Map<String,Double> totals=new LinkedHashMap<>();for(Map<String,Object> i:intervals)totals.merge((String)i.get("effectiveState"),(Double)i.get("durationSec"),Double::sum);
      List<Map<String,Object>> totalRows=totals.entrySet().stream().map(e->Map.<String,Object>of("state",e.getKey(),"durationSec",e.getValue())).toList();
      Map<String,Object> o=new LinkedHashMap<>();o.put("shiftId",d.id());o.put("shiftCode",d.code());o.put("shiftName",d.name());o.put("shiftDate",shiftDate);o.put("occurrenceFrom",start);o.put("occurrenceTo",end);o.put("actualTo",readyTo);o.put("partial",readyTo.isBefore(end));o.put("totals",totalRows);o.put("intervals",intervals);occurrences.add(o);
    }
    if(shiftId!=null&&definitions.isEmpty())throw new BusinessException(404,"NOT_FOUND","Смена не найдена для предприятия машины");
    Map<String,Object> currency=new LinkedHashMap<>();currency.put("dataThrough",machine.dataThrough());currency.put("calculatedAt",machine.calculatedAt());currency.put("stale",machine.stale());currency.put("recalculating",machine.recalculating());
    Map<String,Object> result=new LinkedHashMap<>();result.put("machineId",machineId);result.put("shiftDate",shiftDate);result.put("currency",currency);result.put("occurrences",occurrences);return result;
  }

  @GetMapping("/api/v1/analytics/summary")
  public Map<String,Object> summary(@RequestParam String scope, @RequestParam UUID scopeId,
      @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
      @RequestParam(required=false) UUID shiftId, @RequestParam(required=false) java.time.LocalDate shiftDate,
      @RequestParam(defaultValue="PREVIOUS_PERIOD") String compare) {
    if(shiftId!=null&&shiftDate==null&&from!=null&&to!=null){
      Map<String,Object> result=new LinkedHashMap<>();result.put("scope",scope);result.put("scopeId",scopeId);result.put("shiftId",shiftId);result.put("shiftDate",null);
      result.put("current",period(scope,scopeId,from,to,"AUTO",1000,shiftId,null));
      if("PREVIOUS_PERIOD".equals(compare)){long duration=ChronoUnit.MILLIS.between(from,readyTo(scope,scopeId,from,to));result.put("previous",period(scope,scopeId,from.minusMillis(duration),from,"AUTO",1000,shiftId,null));}
      else if("NONE".equals(compare))result.put("previous",null);else throw invalid("compare должен быть NONE или PREVIOUS_PERIOD");return result;
    }
    if (shiftId != null || shiftDate != null) {
      if (shiftId == null || shiftDate == null || from != null || to != null)
        throw invalid("Укажите либо from/to, либо shiftId/shiftDate");
      List<ShiftSummaryDefinition> defs=jdbc.query("SELECT s.start_local_time,s.end_local_time,e.timezone FROM equipment.shift s JOIN equipment.enterprise e ON e.id=s.enterprise_id WHERE s.id=? AND s.status='ACTIVE'",
          (rs,i)->new ShiftSummaryDefinition(rs.getTime(1).toLocalTime(),rs.getTime(2).toLocalTime(),rs.getString(3)),shiftId);
      if(defs.isEmpty())throw new BusinessException(404,"NOT_FOUND","Смена не найдена");
      ShiftSummaryDefinition d=defs.getFirst();java.time.ZoneId zone=java.time.ZoneId.of(d.timezone());
      java.time.LocalDate endDate=d.end().isAfter(d.start())?shiftDate:shiftDate.plusDays(1);
      from=ShiftCalendar.boundary(shiftDate.atTime(d.start()),zone);to=ShiftCalendar.boundary(endDate.atTime(d.end()),zone);
      if("PREVIOUS_PERIOD".equals(compare)){
        java.time.LocalDate previousDate=shiftDate.minusDays(1);java.time.LocalDate previousEnd=d.end().isAfter(d.start())?previousDate:previousDate.plusDays(1);
        Instant previousFrom=ShiftCalendar.boundary(previousDate.atTime(d.start()),zone),previousTo=ShiftCalendar.boundary(previousEnd.atTime(d.end()),zone);
        Map<String,Object> result=new LinkedHashMap<>();result.put("scope",scope);result.put("scopeId",scopeId);result.put("shiftId",shiftId);result.put("shiftDate",shiftDate);
        Instant currentReady=readyTo(scope,scopeId,from,to);long elapsed=ChronoUnit.MILLIS.between(from,currentReady);Instant elapsedPreviousTo=previousFrom.plusMillis(Math.min(elapsed,ChronoUnit.MILLIS.between(previousFrom,previousTo)));
        result.put("current",period(scope,scopeId,from,to,"AUTO",1000,shiftId,shiftDate));result.put("previous",period(scope,scopeId,previousFrom,elapsedPreviousTo,"AUTO",1000,shiftId,previousDate));return result;
      }
      if(!"NONE".equals(compare))throw invalid("compare должен быть NONE или PREVIOUS_PERIOD");
      Map<String,Object> result=new LinkedHashMap<>();result.put("scope",scope);result.put("scopeId",scopeId);result.put("shiftId",shiftId);result.put("shiftDate",shiftDate);
      result.put("current",period(scope,scopeId,from,to,"AUTO",1000,shiftId,shiftDate));result.put("previous",null);return result;
    }
    if (from == null || to == null) throw invalid("Укажите либо from/to, либо shiftId/shiftDate");
    Map<String,Object> current = period(scope, scopeId, from, to, "AUTO", 1000);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("scope", scope); result.put("scopeId", scopeId); result.put("current", current);
    result.put("shiftId", null); result.put("shiftDate", null);
    if ("PREVIOUS_PERIOD".equals(compare)) {
      long duration = ChronoUnit.MILLIS.between(from, readyTo(scope,scopeId,from,to));
      result.put("previous", period(scope, scopeId, from.minusMillis(duration), from, "AUTO", 1000));
    } else if ("NONE".equals(compare)) result.put("previous", null);
    else throw invalid("compare должен быть NONE или PREVIOUS_PERIOD");
    return result;
  }

  @GetMapping("/api/v1/machines/{machineId}/state-intervals")
  public Map<String,Object> stateIntervals(@PathVariable UUID machineId,@RequestParam Instant from,@RequestParam Instant to,
      @RequestParam(required=false) List<String> state,@RequestParam(required=false) UUID lossReasonId,
      @RequestParam(defaultValue="false") boolean unclassifiedOnly,@RequestParam(defaultValue="false") boolean lossOnly,
      @RequestParam(required=false) String source,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit){
    Instant requestedTo=to;Instant effectiveTo=readyTo("MACHINE",machineId,from,requestedTo);if(limit<1||limit>500)throw invalid("limit должен быть от 1 до 500");
    Instant before=null;if(cursor!=null&&!cursor.isBlank())try{before=Instant.parse(new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8));}catch(RuntimeException e){throw invalid("Некорректный cursor");}
    if(source!=null&&!List.of("CALCULATED","MANUAL").contains(source))throw invalid("Недопустимый источник состояния");
    if(state!=null&&state.stream().anyMatch(s->!List.of("RUN","IDLE","DOWN","SETUP","PLANNED_STOP","OFFLINE").contains(s)))throw invalid("Недопустимый фильтр состояния");
    Map<UUID,Map<String,Object>> reasonSummaries=new HashMap<>();
    StringBuilder sql=new StringBuilder("WITH timeline AS (SELECT effective_from,effective_to,effective_state state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id FROM analytics.machine_effective_state_interval WHERE machine_id=? UNION ALL SELECT effective_from,effective_to,state,state,'CALCULATED',NULL,NULL,NULL FROM analytics.machine_state_interval i WHERE machine_id=? AND NOT EXISTS(SELECT 1 FROM analytics.machine_effective_state_interval e WHERE e.machine_id=i.machine_id)) SELECT * FROM timeline WHERE effective_from<? AND COALESCE(effective_to,?)>? ");
    List<Object> args=new ArrayList<>(List.of(machineId,machineId,Timestamp.from(effectiveTo),Timestamp.from(effectiveTo),Timestamp.from(from)));
    if(state!=null&&!state.isEmpty()){sql.append("AND state IN (").append(String.join(",",Collections.nCopies(state.size(),"?"))).append(") ");args.addAll(state);}
    if(lossReasonId!=null){sql.append("AND loss_reason_id=? ");args.add(lossReasonId);}if(unclassifiedOnly)sql.append("AND loss_reason_id IS NULL ");if(lossOnly)sql.append("AND state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') ");if(source!=null){sql.append("AND state_source=? ");args.add(source);}if(before!=null){sql.append("AND effective_from<? ");args.add(Timestamp.from(before));}
    sql.append("ORDER BY effective_from DESC LIMIT ?");args.add(limit+1);
    List<Map<String,Object>> rows=jdbc.query(sql.toString(),(rs,i)->{Instant rawFrom=rs.getTimestamp("effective_from").toInstant(),a=rawFrom,b=rs.getTimestamp("effective_to")==null?effectiveTo:rs.getTimestamp("effective_to").toInstant();a=a.isBefore(from)?from:a;b=b.isAfter(effectiveTo)?effectiveTo:b;if(!a.isBefore(b))return null;Map<String,Object> m=new LinkedHashMap<>();m.put("_cursorFrom",rawFrom);m.put("from",a);m.put("to",b);m.put("durationSec",ChronoUnit.MILLIS.between(a,b)/1000d);m.put("openEnded",rs.getTimestamp("effective_to")==null);m.put("effectiveState",rs.getString("state"));m.put("calculatedState",rs.getString("calculated_state"));m.put("stateSource",rs.getString("state_source"));m.put("stateIncidentId",rs.getObject("state_incident_id",UUID.class));m.put("reasonIncidentId",rs.getObject("reason_incident_id",UUID.class));m.put("lossReason",reasonSummary(rs.getObject("loss_reason_id",UUID.class),reasonSummaries));m.put("underlyingOffline","OFFLINE".equals(rs.getString("calculated_state")));return m;},args.toArray()).stream().filter(Objects::nonNull).toList();
    boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);String next=null;if(more&&!rows.isEmpty())next=Base64.getUrlEncoder().withoutPadding().encodeToString(rows.getLast().get("_cursorFrom").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));rows.forEach(row->row.remove("_cursorFrom"));
    List<Machine> machineRows=machines("MACHINE",machineId);if(machineRows.isEmpty())throw new BusinessException(404,"NOT_FOUND","Машина не найдена");
    Map<String,Object> cursorInfo=new LinkedHashMap<>();cursorInfo.put("limit",limit);cursorInfo.put("hasMore",more);cursorInfo.put("nextCursor",next);
    Map<String,Object> currency=metadata("MACHINE",machineId,from,requestedTo);
    Map<String,Object> result=new LinkedHashMap<>();result.put("machineId",machineId);result.put("requestedFrom",from);result.put("requestedTo",requestedTo);result.put("actualFrom",rows.isEmpty()?null:rows.getLast().get("from"));result.put("actualTo",rows.isEmpty()?null:rows.getFirst().get("to"));result.put("partial",effectiveTo.isBefore(requestedTo));result.put("items",rows);result.put("cursor",cursorInfo);result.put("currency",currency);return result;
  }

  @GetMapping("/api/v1/analytics/metrics/timeseries")
  public Map<String,Object> timeseries(@RequestParam String scope, @RequestParam UUID scopeId,
      @RequestParam Instant from, @RequestParam Instant to, @RequestParam(required=false) UUID shiftId,
      @RequestParam(defaultValue="AUTO") String resolution) {
    Instant requestedTo=to;Instant effectiveTo=readyTo(scope,scopeId,from,requestedTo);
    String applied = chooseResolution(from, effectiveTo, resolution, 1000);
    List<Map<String,Object>> points = new ArrayList<>();
    List<Machine> scopeMachines=machines(scope,scopeId);
    Instant intervalFrom = ceil(from, applied), intervalTo = floor(effectiveTo, applied);
    Map<Instant,Map<String,Object>> aggregatePoints=aggregateSeries(scope,scopeId,intervalFrom,intervalTo,applied,shiftId);
    for (Instant cursor = intervalFrom; cursor.isBefore(intervalTo); cursor = cursor.plus(step(applied))) {
      Map<String,Object> facts=aggregatePoints.get(cursor);
      points.add(Map.of("bucketStart", cursor, "bucketEnd", cursor.plus(step(applied)),
          "metrics", facts==null?metricMap(Map.of(),scopeMachines):facts));
    }
    Map<String,Object> metadata = metadata(scope, scopeId, from, requestedTo);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("scope", scope); result.put("scopeId", scopeId); result.put("metadata", metadata);
    result.put("resolution", applied); result.put("points", points); result.put("shiftId", shiftId);
    return result;
  }

  @GetMapping("/api/v1/machines/{machineId}/telemetry/series")
  public Map<String,Object> telemetrySeries(@PathVariable UUID machineId,@RequestParam UUID signalId,
      @RequestParam Instant from,@RequestParam Instant to,@RequestParam(defaultValue="AUTO") String resolution) {
    Instant requestedTo=to;Instant effectiveTo=readyTo("MACHINE",machineId,from,requestedTo);
    Map<String,Object> signal=jdbc.query("SELECT id,machine_id,component_id,code,name,description,value_type,signal_kind,semantic_code,custom_semantic_code,unit_code,expected_interval_ms,stale_after_ms,valid_min,valid_max,featured,display_order,status, "
        + "EXISTS(SELECT 1 FROM telemetry.signal_usage u WHERE u.signal_id=equipment.telemetry_signal.id) has_raw_data,created_at,updated_at "
        + "FROM equipment.telemetry_signal WHERE id=? AND machine_id=?",rs->{
      if(!rs.next())return null;
      Map<String,Object> value=new LinkedHashMap<>(); value.put("id",rs.getObject("id",UUID.class));value.put("machineId",rs.getObject("machine_id",UUID.class));value.put("componentId",rs.getObject("component_id",UUID.class)); value.put("code",rs.getString("code"));
      value.put("name",rs.getString("name")); value.put("description",rs.getString("description")); value.put("valueType",rs.getString("value_type"));
      value.put("signalKind",rs.getString("signal_kind")); value.put("semanticCode",rs.getString("semantic_code"));value.put("customSemanticCode",rs.getString("custom_semantic_code"));
      value.put("unitCode",rs.getString("unit_code"));value.put("expectedIntervalMs",rs.getObject("expected_interval_ms"));value.put("staleAfterMs",rs.getObject("stale_after_ms"));
      value.put("validMin",rs.getObject("valid_min"));value.put("validMax",rs.getObject("valid_max"));value.put("featured",rs.getBoolean("featured")); value.put("displayOrder",rs.getObject("display_order")); value.put("status",rs.getString("status"));
      value.put("hasRawData",rs.getBoolean("has_raw_data"));value.put("createdAt",rs.getTimestamp("created_at").toInstant());value.put("updatedAt",rs.getTimestamp("updated_at").toInstant());return value;
    },signalId,machineId);
    if(signal==null)throw new BusinessException(404,"NOT_FOUND","Машина или сигнал не найдены");
    String kind=(String)signal.get("signalKind"),valueType=(String)signal.get("valueType");
    if(!List.of("GAUGE","COUNTER").contains(kind)||!List.of("DOUBLE","LONG").contains(valueType))throw new BusinessException(409,"INCOMPATIBLE_SIGNAL","Ряд доступен только для числовых сигналов GAUGE и COUNTER");
    String applied=chooseResolution(from,effectiveTo,resolution,720);
    Instant start=ceil(from,applied),end=floor(effectiveTo,applied);
    String interval=sqlInterval(applied);
    String aggSql="SELECT date_bin('"+interval+"',bucket_start,'2000-01-01'::timestamptz) bucket_start, "
        + "min(min_value) min_value,max(max_value) max_value,sum(sum_value) sum_value,sum(usable_count) usable_count, "
        + "sum(sample_count) sample_count,sum(good_count) good_count,sum(uncertain_count) uncertain_count,sum(bad_count) bad_count, "
        + "(array_agg(first_value ORDER BY first_observed_at) FILTER(WHERE first_observed_at IS NOT NULL))[1] first_value, "
        + "(array_agg(last_value ORDER BY last_observed_at DESC) FILTER(WHERE last_observed_at IS NOT NULL))[1] last_value, "
        + "sum(delta_value) delta_value,sum(reset_count) reset_count,min(bucket_start) actual_from,max(bucket_start+interval '1 minute') actual_to,max(calculated_at) calculated_at "
        + "FROM analytics.signal_numeric_rollup_1m WHERE signal_id=? AND bucket_start>=? AND bucket_start<? GROUP BY 1 ORDER BY 1";
    Map<Instant,Map<String,Object>> rows=new HashMap<>();
    jdbc.query(aggSql,rs->{
      Map<String,Object> row=new HashMap<>();
      for(String col:List.of("min_value","max_value","sum_value","usable_count","sample_count","good_count","uncertain_count","bad_count","first_value","last_value","delta_value","reset_count","actual_from","actual_to","calculated_at"))row.put(col,rs.getObject(col));
      rows.put(rs.getTimestamp("bucket_start").toInstant(),row);
    },signalId,Timestamp.from(start),Timestamp.from(end));
    List<Map<String,Object>> points=new ArrayList<>();
    for(Instant bucket=start;bucket.isBefore(end);bucket=bucket.plus(step(applied))){
      Map<String,Object> row=rows.get(bucket); Map<String,Object> p=new LinkedHashMap<>();
      p.put("bucketStart",bucket);p.put("bucketEnd",bucket.plus(step(applied)));
      long samples=row==null?0:number(row.get("sample_count")).longValue();
      if("GAUGE".equals(kind)){
        p.put("min",row==null?null:row.get("min_value"));p.put("max",row==null?null:row.get("max_value"));
        p.put("avg",row==null||number(row.get("usable_count")).longValue()==0?null:number(row.get("sum_value")).doubleValue()/number(row.get("usable_count")).doubleValue());
      } else {
        p.put("delta",row==null?null:row.get("delta_value"));p.put("resetCount",row==null?0:number(row.get("reset_count")).longValue());
      }
      p.put("first",row==null?null:row.get("first_value"));p.put("last",row==null?null:row.get("last_value"));
      p.put("sampleCount",samples);p.put("usableCount",row==null?0:number(row.get("usable_count")).longValue());
      p.put("goodCount",row==null?0:number(row.get("good_count")).longValue());p.put("uncertainCount",row==null?0:number(row.get("uncertain_count")).longValue());p.put("badCount",row==null?0:number(row.get("bad_count")).longValue());
      points.add(p);
    }
    Map<String,Object> response=new LinkedHashMap<>(); response.put("machineId",machineId);response.put("signal",signal);
    Map<String,Object> metadata=telemetryMetadata(machineId,signalId,from,requestedTo,applied,rows.values()); response.put("metadata",metadata);response.put("points",points);return response;
  }

  @GetMapping("/api/v1/analytics/losses/timeseries")
  public Map<String,Object> losses(@RequestParam String scope,@RequestParam UUID scopeId,@RequestParam Instant from,
      @RequestParam Instant to,@RequestParam(required=false) UUID shiftId,@RequestParam(defaultValue="AUTO") String resolution,
      @RequestParam(defaultValue="5") int top) {
    Instant requestedTo=to;Instant effectiveTo=readyTo(scope,scopeId,from,requestedTo);
    if(top<1||top>20)throw invalid("top должен быть от 1 до 20");
    String applied=chooseResolution(from,effectiveTo,resolution,1000);Instant start=ceil(from,applied),end=floor(effectiveTo,applied);
    List<Machine> machines=machines(scope,scopeId);if(machines.isEmpty())throw new BusinessException(404,"NOT_FOUND","Область аналитики не найдена");
    String placeholders=String.join(",",Collections.nCopies(machines.size(),"?"));List<Object> args=new ArrayList<>();machines.forEach(m->args.add(m.id()));args.add(Timestamp.from(start));args.add(Timestamp.from(end));if(shiftId!=null)args.add(shiftId);
    String sql="SELECT date_bin('"+sqlInterval(applied)+"',bucket_start,'2000-01-01'::timestamptz) bucket_start,state,loss_reason_id,sum(duration_ms) duration_ms "
        +"FROM analytics.machine_loss_bucket_1m WHERE machine_id IN ("+placeholders+") AND bucket_start>=? AND bucket_start<?"+(shiftId==null?"":" AND shift_id=?")+" GROUP BY 1,state,loss_reason_id ORDER BY 1";
    List<LossRow> rows=jdbc.query(sql,(rs,i)->new LossRow(rs.getTimestamp("bucket_start").toInstant(),rs.getString("state"),rs.getObject("loss_reason_id",UUID.class),rs.getLong("duration_ms")),args.toArray());
    List<UUID> topReasons=rows.stream().filter(r->r.reasonId()!=null&& !"PLANNED_STOP".equals(r.state()))
        .collect(java.util.stream.Collectors.groupingBy(LossRow::reasonId,java.util.stream.Collectors.summingLong(LossRow::durationMs)))
        .entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed().thenComparing(e->e.getKey().toString()))
        .limit(top).map(Map.Entry::getKey).toList();
    Map<Instant,List<LossRow>> lossByBucket=rows.stream().collect(java.util.stream.Collectors.groupingBy(LossRow::bucket));
    List<Map<String,Object>> points=new ArrayList<>();
    for(Instant bucket=start;bucket.isBefore(end);bucket=bucket.plus(step(applied))){
      List<LossRow> own=lossByBucket.getOrDefault(bucket,List.of());
      long idle=sum(own,"IDLE"),down=sum(own,"DOWN"),setup=sum(own,"SETUP"),planned=sum(own,"PLANNED_STOP");
      Map<UUID,Long> reasonDurations=new LinkedHashMap<>();long unclassified=0,other=0;
      for(LossRow r:own){if("PLANNED_STOP".equals(r.state()))continue;if(r.reasonId()==null)unclassified+=r.durationMs();else if(topReasons.contains(r.reasonId()))reasonDurations.merge(r.reasonId(),r.durationMs(),Long::sum);else other+=r.durationMs();}
      List<Map<String,Object>> byReason=new ArrayList<>();if(unclassified>0)byReason.add(lossReason("UNCLASSIFIED",null,unclassified/1000d));
      reasonDurations.forEach((id,d)->byReason.add(lossReason("REASON",id,d/1000d)));if(other>0)byReason.add(lossReason("OTHER",null,other/1000d));
      Map<String,Object> p=new LinkedHashMap<>();p.put("bucketStart",bucket);p.put("bucketEnd",bucket.plus(step(applied)));p.put("idleSec",idle/1000d);p.put("downSec",down/1000d);p.put("setupSec",setup/1000d);p.put("plannedStopSec",planned/1000d);p.put("unclassifiedLossSec",unclassified/1000d);p.put("byReason",byReason);points.add(p);
    }
    Map<String,Object> response=new LinkedHashMap<>();response.put("scope",scope);response.put("scopeId",scopeId);response.put("shiftId",shiftId);response.put("metadata",metadata(scope,scopeId,from,requestedTo));response.put("resolution",applied);response.put("points",points);return response;
  }

  @GetMapping("/api/v1/analytics/losses/pareto")
  public Map<String,Object> pareto(@RequestParam String scope,@RequestParam UUID scopeId,@RequestParam Instant from,
      @RequestParam Instant to,@RequestParam(required=false) UUID shiftId,@RequestParam(required=false) java.time.LocalDate shiftDate,
      @RequestParam(defaultValue="5") int top) {
    Instant requestedTo=to;Instant effectiveTo=readyTo(scope,scopeId,from,requestedTo);if((shiftId==null)!=(shiftDate==null))throw invalid("shiftId и shiftDate передаются вместе");if(top<1||top>20)throw invalid("top должен быть от 1 до 20");
    List<Machine> machines=machines(scope,scopeId);if(machines.isEmpty())throw new BusinessException(404,"NOT_FOUND","Область аналитики не найдена");
    String placeholders=String.join(",",Collections.nCopies(machines.size(),"?"));List<Object> args=new ArrayList<>();machines.forEach(m->args.add(m.id()));
    Instant start=from.truncatedTo(ChronoUnit.MINUTES);if(!start.equals(from))start=start.plus(1,ChronoUnit.MINUTES);Instant end=effectiveTo.truncatedTo(ChronoUnit.MINUTES);
    args.add(Timestamp.from(start));args.add(Timestamp.from(end));if(shiftId!=null){args.add(shiftId);args.add(java.sql.Date.valueOf(shiftDate));}
    List<LossRow> rows=jdbc.query("SELECT NULL::timestamptz bucket_start,state,loss_reason_id,sum(duration_ms) duration_ms FROM analytics.machine_loss_bucket_1m "
        +"WHERE machine_id IN ("+placeholders+") AND bucket_start>=? AND bucket_start<?"+(shiftId==null?"":" AND shift_id=? AND shift_date=?")+" AND state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') GROUP BY state,loss_reason_id",(rs,i)->new LossRow(null,rs.getString("state"),rs.getObject("loss_reason_id",UUID.class),rs.getLong("duration_ms")),args.toArray());
    long total=rows.stream().filter(r->!"PLANNED_STOP".equals(r.state())).mapToLong(LossRow::durationMs).sum();long planned=rows.stream().filter(r->"PLANNED_STOP".equals(r.state())).mapToLong(LossRow::durationMs).sum();
    Map<UUID,Long> named=new HashMap<>();long unclassified=0;for(LossRow r:rows){if("PLANNED_STOP".equals(r.state()))continue;if(r.reasonId()==null)unclassified+=r.durationMs();else named.merge(r.reasonId(),r.durationMs(),Long::sum);}
    List<Map.Entry<UUID,Long>> ordered=named.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed().thenComparing(e->e.getKey().toString())).toList();
    List<Map<String,Object>> rowsOut=new ArrayList<>();long other=0;for(int i=0;i<ordered.size();i++){var e=ordered.get(i);if(i<top)rowsOut.add(paretoRow("REASON",e.getKey(),e.getValue(),total));else other+=e.getValue();}
    if(unclassified>0)rowsOut.add(paretoRow("UNCLASSIFIED",null,unclassified,total));if(other>0)rowsOut.add(paretoRow("OTHER",null,other,total));
    rowsOut.sort(Comparator.<Map<String,Object>,Double>comparing(r->(Double)r.get("durationSec")).reversed().thenComparing(r->String.valueOf(r.get("reasonId"))));
    double cumulative=0;for(Map<String,Object> r:rowsOut){double share=total==0?0:(Double)r.get("durationSec")/(total/1000d);cumulative+=share;r.put("share",share);r.put("cumulativeShare",Math.min(1,cumulative));}
    Map<String,Object> response=new LinkedHashMap<>();response.put("scope",scope);response.put("scopeId",scopeId);response.put("shiftId",shiftId);response.put("shiftDate",shiftDate);response.put("metadata",metadata(scope,scopeId,from,requestedTo));response.put("totalLossSec",total/1000d);response.put("plannedStopSec",planned/1000d);response.put("rows",total==0?List.of():rowsOut);return response;
  }

  private Map<String,Object> period(String scope, UUID scopeId, Instant from, Instant to, String resolution, int maxPoints) {
    return period(scope,scopeId,from,to,resolution,maxPoints,null,null);
  }
  private Map<String,Object> period(String scope, UUID scopeId, Instant from, Instant to, String resolution, int maxPoints,UUID shiftId,java.time.LocalDate shiftDate) {
    Instant requestedTo=to;to=readyTo(scope,scopeId,from,requestedTo);
    Instant alignedFrom = from.truncatedTo(ChronoUnit.MINUTES);
    if (!alignedFrom.equals(from)) alignedFrom=alignedFrom.plus(1,ChronoUnit.MINUTES);
    Instant alignedTo = to.truncatedTo(ChronoUnit.MINUTES);
    Map<String,Object> metrics = aggregate(scope, scopeId, alignedFrom, alignedTo,shiftId,shiftDate);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("metrics", metrics); result.put("metadata", metadata(scope, scopeId, from, requestedTo));
    return result;
  }

  private Map<String,Object> aggregate(String scope, UUID scopeId, Instant from, Instant to) {
    return aggregate(scope,scopeId,from,to,null,null);
  }
  private Map<String,Object> aggregate(String scope, UUID scopeId, Instant from, Instant to,UUID shiftId,java.time.LocalDate shiftDate) {
    if((shiftId==null)!=(shiftDate==null))throw invalid("shiftId и shiftDate передаются вместе");
    List<Machine> machines = machines(scope, scopeId);
    if (machines.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "Analytics scope not found or has no active machines");
    List<MetricCalculator.Totals> totals = new ArrayList<>();
    String unit = machines.stream().map(Machine::unit).filter(Objects::nonNull).distinct().count() == 1
        ? machines.stream().map(Machine::unit).filter(Objects::nonNull).findFirst().orElse(null) : null;
    boolean unitsCompatible = machines.stream().allMatch(m -> Objects.equals(unit, m.unit()))
        && (unit != null || machines.stream().noneMatch(m -> m.rate() != null));
    boolean outputSignals = machines.stream().allMatch(Machine::hasOutput);
    boolean scrapSignals = machines.stream().allMatch(Machine::hasScrap);
    boolean rates = machines.stream().allMatch(m -> m.rate() != null);
    double covered=0,run=0,idle=0,down=0,setup=0,planned=0,effectiveOffline=0,underlyingOffline=0;
    double output=0,scrap=0,rate=0;
    long stops=0,failures=0,recoveries=0;
    long outputRows=0,scrapRows=0;
    String ids = String.join(",", Collections.nCopies(machines.size(), "?"));
    List<Object> args = new ArrayList<>();
    machines.forEach(m -> args.add(m.id()));
    args.add(Timestamp.from(from)); args.add(Timestamp.from(to));
    String sql = "SELECT count(*) rows_count,count(produced_quantity) output_rows,count(scrap_quantity) scrap_rows, "
        + "COALESCE(sum(covered_duration_ms),0) covered,COALESCE(sum(run_duration_ms),0) run,COALESCE(sum(idle_duration_ms),0) idle, "
        + "COALESCE(sum(down_duration_ms),0) down,COALESCE(sum(setup_duration_ms),0) setup,COALESCE(sum(planned_stop_duration_ms),0) planned, "
        + "COALESCE(sum(effective_offline_duration_ms),0) effective_offline,COALESCE(sum(underlying_offline_duration_ms),0) underlying_offline, "
        + "COALESCE(sum(produced_quantity),0) output,COALESCE(sum(scrap_quantity),0) scrap,COALESCE(sum(stop_count),0) stops, "
        + "COALESCE(sum(failure_count),0) failures,COALESCE(sum(recovery_count),0) recoveries FROM analytics.machine_metric_bucket_1m "
        + "WHERE machine_id IN ("+ids+") AND bucket_start>=? AND bucket_start<?"+(shiftId==null?"":" AND shift_id=? AND shift_date=?");
    if(shiftId!=null){args.add(shiftId);args.add(java.sql.Date.valueOf(shiftDate));}
    Map<String,Object> row = jdbc.queryForMap(sql,args.toArray());
    outputRows=((Number)row.get("output_rows")).longValue(); scrapRows=((Number)row.get("scrap_rows")).longValue();
    covered=((Number)row.get("covered")).doubleValue(); run=((Number)row.get("run")).doubleValue(); idle=((Number)row.get("idle")).doubleValue();
    down=((Number)row.get("down")).doubleValue(); setup=((Number)row.get("setup")).doubleValue(); planned=((Number)row.get("planned")).doubleValue();
    effectiveOffline=((Number)row.get("effective_offline")).doubleValue(); underlyingOffline=((Number)row.get("underlying_offline")).doubleValue();
    output=((Number)row.get("output")).doubleValue(); scrap=((Number)row.get("scrap")).doubleValue(); stops=((Number)row.get("stops")).longValue();
    failures=((Number)row.get("failures")).longValue(); recoveries=((Number)row.get("recoveries")).longValue();
    rate=machines.stream().map(Machine::rate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    long expectedRows=((Number)row.get("rows_count")).longValue();
    Double resultOutput = outputSignals && expectedRows > 0 && outputRows == expectedRows ? output : null;
    Double resultScrap = scrapSignals && expectedRows > 0 && scrapRows == expectedRows ? scrap : null;
    var calc = MetricCalculator.calculate(new MetricCalculator.Totals(covered,run,idle,down,setup,planned,effectiveOffline,
        underlyingOffline,resultOutput,resultScrap,rates?rate:null,unit,stops,failures,recoveries),
        unitsCompatible,outputSignals,scrapSignals,rates);
    Map<String,Object> diagnostics = new LinkedHashMap<>(); diagnostics.put("codes",calc.diagnosticCodes());
    diagnostics.put("machineIdsWithoutOutput",machines.stream().filter(m->!m.hasOutput()).map(Machine::id).toList());
    diagnostics.put("machineIdsWithoutNominalRate",machines.stream().filter(m->m.rate()==null).map(Machine::id).toList());
    diagnostics.put("productionUnits",machines.stream().map(Machine::unit).filter(Objects::nonNull).distinct().toList());
    Map<String,Object> values = new LinkedHashMap<>();
    values.put("coveredSec",calc.coveredSec()); values.put("runSec",calc.runSec()); values.put("idleSec",calc.idleSec());
    values.put("downSec",calc.downSec()); values.put("setupSec",calc.setupSec()); values.put("plannedStopSec",calc.plannedStopSec());
    values.put("effectiveOfflineSec",calc.effectiveOfflineSec()); values.put("underlyingOfflineSec",calc.underlyingOfflineSec());
    values.put("plannedProductionSec",calc.plannedProductionSec()); values.put("operatingSec",calc.operatingSec()); values.put("productiveSec",calc.productiveSec());
    values.put("output",calc.output()); values.put("scrap",calc.scrap()); values.put("goodOutput",calc.goodOutput()); values.put("idealOutput",calc.idealOutput());
    values.put("productionUnit",calc.productionUnit()); values.put("stopCount",calc.stopCount()); values.put("failureCount",calc.failureCount());
    values.put("recoveryCount",calc.recoveryCount()); values.put("availability",calc.availability()); values.put("performance",calc.performance());
    values.put("quality",calc.quality()); values.put("oee",calc.oee()); values.put("dataCoverage",calc.dataCoverage());
    values.put("mtbfSec",calc.mtbfSec()); values.put("mttrSec",calc.mttrSec()); values.put("diagnostics",diagnostics);
    return values;
  }

  private Map<Instant,Map<String,Object>> aggregateSeries(String scope,UUID scopeId,Instant from,Instant to,String resolution,UUID shiftId){
    if(!from.isBefore(to))return Map.of();List<Machine> machines=machines(scope,scopeId);if(machines.isEmpty())return Map.of();
    String ids=String.join(",",Collections.nCopies(machines.size(),"?"));List<Object> args=new ArrayList<>();machines.forEach(m->args.add(m.id()));args.add(Timestamp.from(from));args.add(Timestamp.from(to));if(shiftId!=null)args.add(shiftId);
    String sql="SELECT date_bin('"+sqlInterval(resolution)+"',bucket_start,'2000-01-01'::timestamptz) point_start,count(*) rows_count,count(produced_quantity) output_rows,count(scrap_quantity) scrap_rows, "
        +"COALESCE(sum(covered_duration_ms),0) covered,COALESCE(sum(run_duration_ms),0) run,COALESCE(sum(idle_duration_ms),0) idle,COALESCE(sum(down_duration_ms),0) down, "
        +"COALESCE(sum(setup_duration_ms),0) setup,COALESCE(sum(planned_stop_duration_ms),0) planned,COALESCE(sum(effective_offline_duration_ms),0) effective_offline, "
        +"COALESCE(sum(underlying_offline_duration_ms),0) underlying_offline,COALESCE(sum(produced_quantity),0) output,COALESCE(sum(scrap_quantity),0) scrap, "
        +"COALESCE(sum(stop_count),0) stops,COALESCE(sum(failure_count),0) failures,COALESCE(sum(recovery_count),0) recoveries FROM analytics.machine_metric_bucket_1m "
        +"WHERE machine_id IN ("+ids+") AND bucket_start>=? AND bucket_start<?"+(shiftId==null?"":" AND shift_id=?")+" GROUP BY 1 ORDER BY 1";
    Map<Instant,Map<String,Object>> result=new HashMap<>();
    jdbc.query(sql,rs->{Map<String,Object> row=new HashMap<>();for(String c:List.of("rows_count","output_rows","scrap_rows","covered","run","idle","down","setup","planned","effective_offline","underlying_offline","output","scrap","stops","failures","recoveries"))row.put(c,rs.getObject(c));result.put(rs.getTimestamp("point_start").toInstant(),metricMap(row,machines));},args.toArray());
    return result;
  }

  private Map<String,Object> metricMap(Map<String,Object> row,List<Machine> machines){
    String unit=machines.stream().map(Machine::unit).filter(Objects::nonNull).distinct().count()==1?machines.stream().map(Machine::unit).filter(Objects::nonNull).findFirst().orElse(null):null;
    boolean compatible=machines.stream().allMatch(m->Objects.equals(unit,m.unit()))&&(unit!=null||machines.stream().noneMatch(m->m.rate()!=null));
    boolean hasOutput=machines.stream().allMatch(Machine::hasOutput),hasScrap=machines.stream().allMatch(Machine::hasScrap),hasRate=machines.stream().allMatch(m->m.rate()!=null);
    long rows=number(row.get("rows_count")).longValue(),outputRows=number(row.get("output_rows")).longValue(),scrapRows=number(row.get("scrap_rows")).longValue();
    Double output=hasOutput&&rows>0&&outputRows==rows?number(row.get("output")).doubleValue():null;
    Double scrap=hasScrap&&rows>0&&scrapRows==rows?number(row.get("scrap")).doubleValue():null;
    double rate=machines.stream().map(Machine::rate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    var calc=MetricCalculator.calculate(new MetricCalculator.Totals(number(row.get("covered")).doubleValue(),number(row.get("run")).doubleValue(),number(row.get("idle")).doubleValue(),
        number(row.get("down")).doubleValue(),number(row.get("setup")).doubleValue(),number(row.get("planned")).doubleValue(),number(row.get("effective_offline")).doubleValue(),number(row.get("underlying_offline")).doubleValue(),
        output,scrap,hasRate?rate:null,unit,number(row.get("stops")).longValue(),number(row.get("failures")).longValue(),number(row.get("recoveries")).longValue()),compatible,hasOutput,hasScrap,hasRate);
    Map<String,Object> d=new LinkedHashMap<>();d.put("codes",calc.diagnosticCodes());d.put("machineIdsWithoutOutput",machines.stream().filter(m->!m.hasOutput()).map(Machine::id).toList());d.put("machineIdsWithoutNominalRate",machines.stream().filter(m->m.rate()==null).map(Machine::id).toList());d.put("productionUnits",machines.stream().map(Machine::unit).filter(Objects::nonNull).distinct().toList());
    Map<String,Object> v=new LinkedHashMap<>();v.put("coveredSec",calc.coveredSec());v.put("runSec",calc.runSec());v.put("idleSec",calc.idleSec());v.put("downSec",calc.downSec());v.put("setupSec",calc.setupSec());v.put("plannedStopSec",calc.plannedStopSec());
    v.put("effectiveOfflineSec",calc.effectiveOfflineSec());v.put("underlyingOfflineSec",calc.underlyingOfflineSec());v.put("plannedProductionSec",calc.plannedProductionSec());v.put("operatingSec",calc.operatingSec());v.put("productiveSec",calc.productiveSec());
    v.put("output",calc.output());v.put("scrap",calc.scrap());v.put("goodOutput",calc.goodOutput());v.put("idealOutput",calc.idealOutput());v.put("productionUnit",calc.productionUnit());v.put("stopCount",calc.stopCount());v.put("failureCount",calc.failureCount());v.put("recoveryCount",calc.recoveryCount());
    v.put("availability",calc.availability());v.put("performance",calc.performance());v.put("quality",calc.quality());v.put("oee",calc.oee());v.put("dataCoverage",calc.dataCoverage());v.put("mtbfSec",calc.mtbfSec());v.put("mttrSec",calc.mttrSec());v.put("diagnostics",d);return v;
  }

  private Map<String,Object> metadata(String scope, UUID scopeId, Instant requestedFrom, Instant requestedTo) {
    List<UUID> ids = machines(scope, scopeId).stream().map(Machine::id).toList();
    if (ids.isEmpty()) throw new BusinessException(404,"NOT_FOUND","Область аналитики не найдена");
    String qs=String.join(",",Collections.nCopies(ids.size(),"?"));
    List<Object> args=new ArrayList<>(ids); args.add(Timestamp.from(ceilMinute(requestedFrom))); args.add(Timestamp.from(requestedTo.truncatedTo(ChronoUnit.MINUTES)));
    Map<String,Object> row=jdbc.queryForMap("SELECT min(bucket_start) actual_from,max(bucket_start+interval '1 minute') actual_to,max(calculated_at) calculated_at "
        + "FROM analytics.machine_metric_bucket_1m WHERE machine_id IN ("+qs+") AND bucket_start>=? AND bucket_start<?",args.toArray());
    Instant actualFrom=timestamp(row.get("actual_from")),actualTo=timestamp(row.get("actual_to")),calculated=timestamp(row.get("calculated_at"));
    List<Object> currArgs=new ArrayList<>(ids);
    Map<String,Object> currency=jdbc.queryForMap("SELECT min(data_through) data_through,min(calculated_at) calculated_at, "
        + "(EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id IN ("+qs+") AND w.status<>'IDLE') OR "
        + "EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id IN ("+qs+") AND h.consumed_at IS NULL)) stale, "
        + "EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id IN ("+qs+") AND w.status='RUNNING') recalculating "
        + "FROM analytics.machine_current_state WHERE machine_id IN ("+qs+")",concat(currArgs,currArgs,currArgs,currArgs));
    Instant dataThrough=timestamp(currency.get("data_through"));
    Map<String,Object> result=new LinkedHashMap<>(); result.put("requestedFrom",requestedFrom); result.put("requestedTo",requestedTo);
    result.put("actualFrom",actualFrom); result.put("actualTo",actualTo); result.put("dataThrough",dataThrough);
    result.put("calculatedAt",calculated); result.put("partial",actualFrom==null||actualFrom.isAfter(requestedFrom)||actualTo==null||actualTo.isBefore(requestedTo)||dataThrough==null||dataThrough.isBefore(requestedTo));
    result.put("stale",Boolean.TRUE.equals(currency.get("stale"))); result.put("recalculating",Boolean.TRUE.equals(currency.get("recalculating")));
    return result;
  }

  private List<Machine> machines(String scope, UUID id) {
    String where=switch(scope){case "MACHINE"->"m.id=?";case "LINE"->"m.line_id=?";case "ENTERPRISE"->"m.enterprise_id=?";default->throw invalid("scope должен быть MACHINE, LINE или ENTERPRISE");};
    String sql="SELECT m.id,m.nominal_rate,m.production_unit_code, "
        + "EXISTS(SELECT 1 FROM equipment.telemetry_signal s WHERE s.machine_id=m.id AND s.status='ACTIVE' AND s.signal_kind='COUNTER' AND s.semantic_code='PRODUCTION_COUNT') has_output, "
        + "EXISTS(SELECT 1 FROM equipment.telemetry_signal s WHERE s.machine_id=m.id AND s.status='ACTIVE' AND s.signal_kind='COUNTER' AND s.semantic_code='SCRAP_COUNT') has_scrap "
        + "FROM equipment.machine m WHERE "+where+" AND m.status='ACTIVE' ORDER BY m.id LIMIT 1001";
    List<Machine> rows=jdbc.query(sql,(rs,i)->new Machine(rs.getObject("id",UUID.class),rs.getBigDecimal("nominal_rate")==null?null:rs.getBigDecimal("nominal_rate").doubleValue(),rs.getString("production_unit_code"),rs.getBoolean("has_output"),rs.getBoolean("has_scrap")),id);
    if(rows.size()>1000)throw invalid("Область аналитики превышает 1000 машин");
    return rows;
  }

  /** Reads stop at the wall clock: a live period keeps requestedTo, while metadata reports dataThrough and partial. */
  private Instant readyTo(String scope,UUID scopeId,Instant from,Instant requestedTo){
    validateRange(from,requestedTo,10_000);
    Instant now=Instant.now();
    return requestedTo.isBefore(now)?requestedTo:now;
  }

  private void validateRange(Instant from,Instant to,int maxPoints){
    Instant now=Instant.now();
    if(from==null||to==null||!from.isBefore(to))throw invalidRange("to","Дата окончания должна быть позже даты начала");
    if(!from.isBefore(now))throw invalidRange("from","Дата начала должна быть в прошлом");
    if(from.isBefore(now.minus(90,ChronoUnit.DAYS)))throw invalidRange("from","История доступна за последние 90 дней");
    if(ChronoUnit.MINUTES.between(from,to)>90L*24*60)throw invalidRange("to","Диапазон не может превышать 90 дней");
  }
  private String chooseResolution(Instant from,Instant to,String resolution,int limit){
    if(!List.of("AUTO","5m","15m","1h","1d").contains(resolution))throw invalid("Неподдерживаемое разрешение");
    if("AUTO".equals(resolution)){
      long mins=ChronoUnit.MINUTES.between(from,to);
      String selected=mins<=12L*60?"5m":mins<=2L*24*60?"15m":mins<=14L*24*60?"1h":"1d";
      while(((ChronoUnit.SECONDS.between(from,to)+step(selected).getSeconds()-1)/step(selected).getSeconds())>limit){
        selected=switch(selected){case "5m"->"15m";case "15m"->"1h";case "1h"->"1d";default->throw invalid("Не удалось подобрать разрешение: слишком длинный период");};
      }
      return selected;
    }
    long points=(ChronoUnit.SECONDS.between(from,to)+step(resolution).getSeconds()-1)/step(resolution).getSeconds();
    if(points>limit)throw invalid("Период слишком длинный для выбранного разрешения");
    return resolution;
  }
  private java.time.Duration step(String r){return switch(r){case "5m"->java.time.Duration.ofMinutes(5);case "15m"->java.time.Duration.ofMinutes(15);case "1h"->java.time.Duration.ofHours(1);case "1d"->java.time.Duration.ofDays(1);default->throw invalid("Неподдерживаемое разрешение");};}
  private Instant floor(Instant t,String r){var d=step(r);return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(),d.getSeconds())*d.getSeconds());}
  private Instant ceil(Instant t,String r){Instant f=floor(t,r);return f.equals(t)?t:f.plus(step(r));}
  private Instant ceilMinute(Instant t){Instant f=t.truncatedTo(ChronoUnit.MINUTES);return f.equals(t)?t:f.plus(1,ChronoUnit.MINUTES);}
  private Instant timestamp(Object value){return value==null?null:((java.sql.Timestamp)value).toInstant();}
  private String sqlInterval(String resolution){return switch(resolution){case "5m"->"5 minutes";case "15m"->"15 minutes";case "1h"->"1 hour";case "1d"->"1 day";default->throw invalid("Неподдерживаемое разрешение");};}
  private Number number(Object value){return value instanceof Number n?n:0;}
  private long sum(List<LossRow> rows,String state){return rows.stream().filter(r->state.equals(r.state())).mapToLong(LossRow::durationMs).sum();}
  private Map<String,Object> lossReason(String kind,UUID reasonId,double duration){Map<String,Object> row=new LinkedHashMap<>();row.put("kind",kind);row.put("reasonId",reasonId);Map<String,Object> summary=reasonSummary(reasonId);row.put("reasonCode",summary==null?null:summary.get("code"));row.put("reasonName",summary==null?null:summary.get("name"));row.put("category",summary==null?null:summary.get("category"));row.put("color",summary==null?null:summary.get("color"));row.put("durationSec",duration);return row;}
  private Map<String,Object> reasonSummary(UUID id){if(id==null)return null;return jdbc.query("SELECT id,code,name,category,color,status FROM analytics.loss_reason WHERE id=?",rs->{if(!rs.next())return null;Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getObject("id",UUID.class));m.put("code",rs.getString("code"));m.put("name",rs.getString("name"));m.put("category",rs.getString("category"));m.put("color",rs.getString("color"));m.put("status",rs.getString("status"));return m;},id);}
  private Map<String,Object> reasonSummary(UUID id,Map<UUID,Map<String,Object>> cache){if(id==null)return null;if(!cache.containsKey(id))cache.put(id,reasonSummary(id));return cache.get(id);}
  private Map<String,Object> paretoRow(String kind,UUID reasonId,long duration,long total){Map<String,Object> row=lossReason(kind,reasonId,duration/1000d);row.put("share",total==0?0:(duration/1000d)/(total/1000d));row.put("cumulativeShare",0d);return row;}
  private Map<String,Object> telemetryMetadata(UUID machineId,UUID signalId,Instant requestedFrom,Instant requestedTo,String resolution,Collection<Map<String,Object>> ignored){
    Map<String,Object> row=jdbc.queryForMap("SELECT min(bucket_start) actual_from,max(bucket_start+interval '1 minute') actual_to,max(calculated_at) calculated_at FROM analytics.signal_numeric_rollup_1m WHERE signal_id=? AND bucket_start>=? AND bucket_start<?",
        signalId,Timestamp.from(ceil(requestedFrom,resolution)),Timestamp.from(floor(requestedTo,resolution)));
    Map<String,Object> current=jdbc.queryForMap("SELECT c.data_through,c.calculated_at,(EXISTS(SELECT 1 FROM telemetry.recalculation_hint h WHERE h.machine_id=c.machine_id AND h.consumed_at IS NULL) OR EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status<>'IDLE')) stale,EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=c.machine_id AND w.status='RUNNING') recalculating FROM analytics.machine_current_state c WHERE c.machine_id=?",machineId);
    Instant actualFrom=timestamp(row.get("actual_from")),actualTo=timestamp(row.get("actual_to")),dataThrough=timestamp(current.get("data_through"));
    Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("requestedFrom",requestedFrom);metadata.put("requestedTo",requestedTo);metadata.put("actualFrom",actualFrom);metadata.put("actualTo",actualTo);
    metadata.put("dataThrough",dataThrough);metadata.put("calculatedAt",timestamp(row.get("calculated_at")));metadata.put("partial",actualFrom==null||actualFrom.isAfter(requestedFrom)||actualTo==null||actualTo.isBefore(requestedTo)||dataThrough==null||dataThrough.isBefore(requestedTo));
    metadata.put("stale",Boolean.TRUE.equals(current.get("stale")));metadata.put("recalculating",Boolean.TRUE.equals(current.get("recalculating")));metadata.put("resolution",resolution);return metadata;
  }
  private Object[] concat(List<Object> first,List<Object> second,List<Object> third,List<Object> fourth){List<Object> all=new ArrayList<>();all.addAll(first);all.addAll(second);all.addAll(third);all.addAll(fourth);return all.toArray();}
  private BusinessException invalid(String message){return new BusinessException(400,"VALIDATION_FAILED",message);}
  private BusinessException invalidRange(String path,String message){return new BusinessException(400,"INVALID_RANGE",message,List.of(new ru.fkit.bi.analytics.api.ErrorResponse.FieldError(path,"INVALID_RANGE",message)),Map.of());}
  private record MachineEnterprise(UUID enterpriseId,String timezone,Instant dataThrough,Instant calculatedAt,boolean stale,boolean recalculating) { }
  private record ShiftOccurrence(UUID id,String code,String name,java.time.LocalTime start,java.time.LocalTime end,
      java.time.LocalDate validFrom,java.time.LocalDate validTo,int sortOrder) { }
  private record ShiftSummaryDefinition(java.time.LocalTime start,java.time.LocalTime end,String timezone) { }
  private record Machine(UUID id,Double rate,String unit,boolean hasOutput,boolean hasScrap) { }
  private record LossRow(Instant bucket,String state,UUID reasonId,long durationMs) { }
}
