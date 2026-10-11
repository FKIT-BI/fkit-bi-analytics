package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.fkit.bi.analytics.api.BusinessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Builds a bounded replacement state timeline in durable staging from retained raw events. */
@Service
public class HistoricalStateReplayService {
  private static final int MAX_EVENTS=500_000;
  private final JdbcTemplate jdbc;private final ObjectMapper json;
  private final HistoricalStateReconstructor reconstructor=new HistoricalStateReconstructor();
  public HistoricalStateReplayService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

  public void validate(UUID machineId,Instant dirtyFrom,boolean fullHistory){
    Machine machine=jdbc.query("SELECT m.coverage_from,c.data_through,c.state FROM equipment.machine m LEFT JOIN analytics.machine_current_state c ON c.machine_id=m.id WHERE m.id=?",
        rs->rs.next()?new Machine(instant(rs,"coverage_from"),instant(rs,"data_through"),rs.getString("state")):null,machineId);
    if(machine==null||machine.coverageFrom()==null||machine.dataThrough()==null)throw new BusinessException(409,"ANALYTICS_NOT_READY","Machine analytics has no state coverage");
    String configText=jdbc.query("SELECT configuration::text FROM analytics.machine_state_config WHERE machine_id=?",rs->rs.next()?rs.getString(1):null,machineId);
    if(configText==null)throw new BusinessException(409,"ANALYTICS_NOT_READY","Machine has no current state rules");
    com.fasterxml.jackson.databind.JsonNode config;try{config=json.readTree(configText);}catch(Exception e){throw new BusinessException(409,"ANALYTICS_NOT_READY","Machine state configuration is invalid");}
    Instant replayFrom=replayFrom(machineId,machine,config,dirtyFrom,fullHistory);
    long stale=jdbc.query("SELECT COALESCE(max(COALESCE(stale_after_ms,expected_interval_ms*3,60000)),0) FROM equipment.telemetry_signal WHERE machine_id=? AND status='ACTIVE'",rs->rs.next()?rs.getLong(1):0L,machineId);
    Instant retainedFrom=fullHistory?replayFrom:replayFrom.minusMillis(stale);
    Instant replayTo=machine.dataThrough().isAfter(dirtyFrom)?machine.dataThrough():dirtyFrom;
    try{if(retainedFrom.isBefore(Instant.now().minus(90,ChronoUnit.DAYS)))throw new IllegalStateException("retention");lockAndCheckPartitionCoverage(retainedFrom,replayTo);}
    catch(RuntimeException e){throw new BusinessException(422,"REPLAY_CONTEXT_NOT_RETAINED","Required preceding raw telemetry context is outside retained partitions");}
    Long count=jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement r JOIN equipment.telemetry_signal s ON s.id=r.signal_id WHERE s.machine_id=? AND s.status='ACTIVE' AND r.observed_at>=? AND r.observed_at<?",Long.class,machineId,Timestamp.from(retainedFrom),Timestamp.from(replayTo));
    if(count!=null&&count>MAX_EVENTS)throw new BusinessException(422,"REPLAY_EVENT_LIMIT","Reconstruction exceeds the bounded raw event limit");
  }

  @Transactional(timeout=45)
  public void stage(TelemetryWorkRepository.WorkItem item){
    jdbc.execute("SET LOCAL statement_timeout='40s'");
    Machine machine=jdbc.query("SELECT m.coverage_from,c.data_through,c.state FROM equipment.machine m LEFT JOIN analytics.machine_current_state c ON c.machine_id=m.id WHERE m.id=?",
        rs->rs.next()?new Machine(instant(rs,"coverage_from"),instant(rs,"data_through"),rs.getString("state")):null,item.machineId());
    if(machine==null||machine.coverageFrom()==null)throw new IllegalStateException("ANALYTICS_NOT_READY");
    Instant replayTo=machine.dataThrough()==null||machine.dataThrough().isBefore(item.dirtyTo())?item.dirtyTo():machine.dataThrough();
    String configText=jdbc.query("SELECT configuration::text FROM analytics.machine_state_config WHERE machine_id=?",rs->rs.next()?rs.getString(1):null,item.machineId());
    if(configText==null)throw new IllegalStateException("STATE_CONFIGURATION_NOT_FOUND");
    com.fasterxml.jackson.databind.JsonNode config;try{config=json.readTree(configText);}catch(Exception e){throw new IllegalStateException("STATE_CONFIGURATION_INVALID",e);}
    Map<UUID,HistoricalStateReconstructor.SignalSpec> specs=new HashMap<>();
    jdbc.query("SELECT id,value_type,COALESCE(stale_after_ms,expected_interval_ms*3,60000) stale_ms FROM equipment.telemetry_signal WHERE machine_id=? AND status='ACTIVE'",
        rs->{specs.put(rs.getObject("id",UUID.class),new HistoricalStateReconstructor.SignalSpec(rs.getString("value_type"),rs.getLong("stale_ms")));},item.machineId());
    boolean fullHistory=item.recalculationJobId()!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.state_recalculation_job WHERE id=? AND scope='ALL')",Boolean.class,item.recalculationJobId()));
    Instant replayFrom=replayFrom(item.machineId(),machine,config,item.dirtyFrom(),fullHistory);
    String preceding=config.path("defaultState").asText("IDLE");
    if(!fullHistory){List<String> anchor=jdbc.query("SELECT state FROM analytics.machine_state_interval WHERE machine_id=? AND effective_from<=? ORDER BY effective_from DESC LIMIT 1",(rs,i)->rs.getString(1),item.machineId(),Timestamp.from(replayFrom));if(!anchor.isEmpty())preceding=anchor.getFirst();}
    replayFrom=replayFrom.truncatedTo(ChronoUnit.MILLIS);replayTo=replayTo.truncatedTo(ChronoUnit.MILLIS);
    if(!replayFrom.isBefore(replayTo))throw new IllegalStateException("REPLAY_RANGE_EMPTY");
    long maxStale=specs.values().stream().mapToLong(HistoricalStateReconstructor.SignalSpec::staleAfterMs).max().orElse(0L);
    Instant carryFrom=fullHistory?replayFrom:replayFrom.minusMillis(maxStale);
    if(carryFrom.isBefore(Instant.now().minus(90,ChronoUnit.DAYS)))throw new IllegalStateException("REPLAY_CONTEXT_NOT_RETAINED");
    requireRetainedPartitions(carryFrom,replayTo);
    List<HistoricalStateReconstructor.Event> carryIn=fullHistory?List.of():loadCarryIn(item.machineId(),carryFrom,replayFrom,specs);
    List<HistoricalStateReconstructor.Event> events=jdbc.query("SELECT r.signal_id,s.value_type,r.value_long,r.value_double,r.value_boolean,r.value_text,r.observed_at,b.received_at,r.source_quality,r.validation_flags "
        + "FROM telemetry.raw_measurement r JOIN equipment.telemetry_signal s ON s.id=r.signal_id JOIN telemetry.ingest_batch b ON b.id=r.ingest_batch_id "
        + "WHERE s.machine_id=? AND s.status='ACTIVE' AND r.observed_at>=? AND r.observed_at<? ORDER BY r.observed_at,r.ingest_batch_id,r.ordinal LIMIT ?",
        (rs,i)->{String type=rs.getString("value_type");Object value=switch(type){case "DOUBLE"->rs.getObject("value_double");case "LONG"->rs.getObject("value_long");case "BOOLEAN"->rs.getObject("value_boolean");default->rs.getString("value_text");};Instant observed=instant(rs,"observed_at");return new HistoricalStateReconstructor.Event(rs.getObject("signal_id",UUID.class),type,value,observed,instant(rs,"received_at"),specs.get(rs.getObject("signal_id",UUID.class))==null?60_000:specs.get(rs.getObject("signal_id",UUID.class)).staleAfterMs(),rs.getInt("source_quality"),rs.getInt("validation_flags"));},item.machineId(),Timestamp.from(replayFrom),Timestamp.from(replayTo),MAX_EVENTS+1);
    if(events.size()>MAX_EVENTS)throw new IllegalStateException("REPLAY_EVENT_LIMIT");
    List<HistoricalStateReconstructor.Interval> intervals=reconstructor.reconstruct(config,specs,carryIn,events,replayFrom,replayTo,machine.coverageFrom(),preceding);
    jdbc.update("DELETE FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?",item.machineId(),item.generation());
    for(var interval:intervals)jdbc.update("INSERT INTO analytics.staging_machine_state_interval(machine_id,effective_from,effective_to,state,detected_at,work_generation) VALUES (?,?,?,?,?,?)",
        item.machineId(),Timestamp.from(interval.from()),interval.to()==null?null:Timestamp.from(interval.to()),interval.state(),Timestamp.from(Instant.now()),item.generation());
  }

  private Instant replayFrom(UUID machineId,Machine machine,com.fasterxml.jackson.databind.JsonNode config,Instant dirtyFrom,boolean fullHistory){
    if(fullHistory)return machine.coverageFrom();
    long context=config.path("offlineAfterMs").asLong(60_000);
    for(var rule:config.path("rules"))context=Math.max(context,rule.path("activationDelayMs").asLong(0)+rule.path("releaseDelayMs").asLong(0));
    List<Long> stale=jdbc.query("SELECT COALESCE(stale_after_ms,expected_interval_ms*3,60000) FROM equipment.telemetry_signal WHERE machine_id=? AND status='ACTIVE'",(rs,i)->rs.getLong(1),machineId);
    for(Long age:stale)context=Math.max(context,age);
    Instant contextStart=dirtyFrom.minusMillis(context);
    List<Instant> anchors=jdbc.query("SELECT effective_from FROM analytics.machine_state_interval WHERE machine_id=? AND effective_from<=? ORDER BY effective_from DESC LIMIT 1",(rs,i)->rs.getTimestamp(1).toInstant(),machineId,Timestamp.from(contextStart));
    return anchors.isEmpty()?machine.coverageFrom():anchors.getFirst();
  }
  private List<HistoricalStateReconstructor.Event> loadCarryIn(UUID machineId,Instant from,Instant to,Map<UUID,HistoricalStateReconstructor.SignalSpec> specs){
    return jdbc.query("SELECT DISTINCT ON(r.signal_id) r.signal_id,s.value_type,r.value_long,r.value_double,r.value_boolean,r.value_text,r.observed_at,b.received_at,r.source_quality,r.validation_flags FROM telemetry.raw_measurement r JOIN equipment.telemetry_signal s ON s.id=r.signal_id JOIN telemetry.ingest_batch b ON b.id=r.ingest_batch_id WHERE s.machine_id=? AND s.status='ACTIVE' AND r.observed_at<? AND r.observed_at>=? AND r.source_quality=0 AND (r.validation_flags&1)=0 ORDER BY r.signal_id,r.observed_at DESC,r.ingest_batch_id DESC,r.ordinal DESC",
        (rs,i)->{String type=rs.getString("value_type");Object value=switch(type){case "DOUBLE"->rs.getObject("value_double");case "LONG"->rs.getObject("value_long");case "BOOLEAN"->rs.getObject("value_boolean");default->rs.getString("value_text");};UUID signal=rs.getObject("signal_id",UUID.class);Instant observed=instant(rs,"observed_at");return new HistoricalStateReconstructor.Event(signal,type,value,observed,instant(rs,"received_at"),specs.get(signal)==null?60_000:specs.get(signal).staleAfterMs(),rs.getInt("source_quality"),rs.getInt("validation_flags"));},machineId,Timestamp.from(to),Timestamp.from(from));
  }
  private void requireRetainedPartitions(Instant from,Instant to){LocalDate day=from.atZone(ZoneOffset.UTC).toLocalDate(),last=to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();
    lockAndCheckPartitionCoverage(day,last);}
  private void lockAndCheckPartitionCoverage(Instant from,Instant to){LocalDate day=from.atZone(ZoneOffset.UTC).toLocalDate(),last=to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();lockAndCheckPartitionCoverage(day,last);}
  private void lockAndCheckPartitionCoverage(LocalDate day,LocalDate last){
    for(LocalDate d=day;!d.isAfter(last);d=d.plusDays(1)){
      Boolean lock=jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(telemetry.raw_partition_day_lock_key(?))",Boolean.class,java.sql.Date.valueOf(d));
      if(!Boolean.TRUE.equals(lock))throw new IllegalStateException("RAW_PARTITION_BARRIER_BUSY");
    }
    checkPartitionCoverage(day,last);}
  private void checkPartitionCoverage(Instant from,Instant to){LocalDate day=from.atZone(ZoneOffset.UTC).toLocalDate(),last=to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();checkPartitionCoverage(day,last);}
  private void checkPartitionCoverage(LocalDate day,LocalDate last){Integer count=jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_partition_registry WHERE status='ACTIVE' AND partition_day>=? AND partition_day<=?",Integer.class,java.sql.Date.valueOf(day),java.sql.Date.valueOf(last));long expected=ChronoUnit.DAYS.between(day,last)+1;if(count==null||count.longValue()!=expected)throw new IllegalStateException("REPLAY_CONTEXT_NOT_RETAINED");}
  private static Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException{var t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
  private record Machine(Instant coverageFrom,Instant dataThrough,String state){}
}
