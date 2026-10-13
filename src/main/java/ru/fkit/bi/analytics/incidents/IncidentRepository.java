package ru.fkit.bi.analytics.incidents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.incidents.LossReasonModels.LossReason;
import ru.fkit.bi.analytics.incidents.ManualIncidentModels.*;

@Repository
class IncidentRepository {
  final JdbcTemplate jdbc;
  IncidentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

  static final RowMapper<LossReason> REASON=(r,n)->new LossReason(r.getObject("id",UUID.class),r.getObject("enterprise_id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),r.getString("category"),r.getString("color"),r.getString("status"),r.getBoolean("used"),instant(r,"created_at"),instant(r,"updated_at"));
  Optional<LossReason> reason(UUID id){return jdbc.query("SELECT r.*,EXISTS(SELECT 1 FROM analytics.manual_incident i WHERE i.loss_reason_id=r.id) used FROM analytics.loss_reason r WHERE r.id=?",REASON,id).stream().findFirst();}
  List<LossReason> reasonPage(UUID e,String status,String category,String search,int page,int size){String q=search==null?null:"%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";return jdbc.query("SELECT r.*,EXISTS(SELECT 1 FROM analytics.manual_incident i WHERE i.loss_reason_id=r.id) used FROM analytics.loss_reason r WHERE enterprise_id=? AND (?::text IS NULL OR status=?::text) AND (?::text IS NULL OR category=?::text) AND (?::text IS NULL OR code ILIKE ?::text ESCAPE '!' OR name ILIKE ?::text ESCAPE '!') ORDER BY name,id LIMIT ? OFFSET ?",REASON,e,status,status,category,category,search,q,q,size,(long)page*size);}
  long reasonCount(UUID e,String status,String category,String search){String q=search==null?null:"%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";return jdbc.queryForObject("SELECT count(*) FROM analytics.loss_reason WHERE enterprise_id=? AND (?::text IS NULL OR status=?::text) AND (?::text IS NULL OR category=?::text) AND (?::text IS NULL OR code ILIKE ?::text ESCAPE '!' OR name ILIKE ?::text ESCAPE '!')",Long.class,e,status,status,category,category,search,q,q);}
  boolean enterpriseExists(UUID e){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.enterprise WHERE id=?)",Boolean.class,e));}
  LossReason create(UUID id,UUID e,LossReasonModels.Create v){jdbc.update("INSERT INTO analytics.loss_reason(id,enterprise_id,code,name,description,category,color) VALUES(?,?,?,?,?,?,?)",id,e,v.code(),v.name().trim(),v.description(),v.category(),v.color());return reason(id).orElseThrow();}
  void lockReason(UUID id){jdbc.query("SELECT id FROM analytics.loss_reason WHERE id=? FOR UPDATE",(r,n)->r.getObject(1),id);}
  void updateReason(UUID id,LossReasonModels.Update v){jdbc.update("UPDATE analytics.loss_reason SET name=?,description=?,category=?,color=? WHERE id=?",v.name().trim(),v.description(),v.category(),v.color(),id);}
  void reasonStatus(UUID id,String status){jdbc.update("UPDATE analytics.loss_reason SET status=? WHERE id=?",status,id);}

  record MachineData(UUID enterprise,Instant coverage,Instant through){}
  MachineData machine(UUID id){return jdbc.query("SELECT m.enterprise_id,m.coverage_from,c.data_through FROM equipment.machine m LEFT JOIN analytics.machine_current_state c ON c.machine_id=m.id WHERE m.id=?",r->r.next()?new MachineData(r.getObject(1,UUID.class),instant(r,"coverage_from"),instant(r,"data_through")):null,id);}
  boolean reasonOwned(UUID id,UUID enterprise){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.loss_reason WHERE id=? AND enterprise_id=?)",Boolean.class,id,enterprise));}
  boolean reasonActive(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.loss_reason WHERE id=? AND status='ACTIVE')",Boolean.class,id));}
  boolean eligibleCoverage(UUID machine,Instant from,Instant to){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM analytics.machine_effective_state_interval WHERE machine_id=? AND effective_state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') AND effective_from<COALESCE(?,(SELECT data_through FROM analytics.machine_current_state WHERE machine_id=?)) AND COALESCE(effective_to,'infinity')>? ) OR EXISTS(SELECT 1 FROM analytics.machine_state_interval WHERE machine_id=? AND state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') AND effective_from<COALESCE(?,(SELECT data_through FROM analytics.machine_current_state WHERE machine_id=?)) AND COALESCE(effective_to,'infinity')>?)",Boolean.class,machine,ts(to),machine,ts(from),machine,ts(to),machine,ts(from)));}
  Incident incident(UUID id){return jdbc.query(INCIDENT_SQL+" WHERE i.id=?",IncidentRepository::mapIncident,id).stream().findFirst().orElse(null);}
  void lockMachine(UUID id){jdbc.query("SELECT id FROM equipment.machine WHERE id=? FOR UPDATE",(r,n)->r.getObject(1),id);}
  List<Incident> overlap(UUID machine,UUID exclude,Instant from,Instant to,boolean state,boolean reason){return jdbc.query("SELECT i.id FROM analytics.manual_incident i WHERE i.machine_id=? AND i.cancelled_at IS NULL AND i.id<>COALESCE(?::uuid,i.id) AND i.starts_at<COALESCE(?::timestamptz,'infinity'::timestamptz) AND COALESCE(i.ends_at,'infinity'::timestamptz)>?::timestamptz AND ((? AND i.state_override IS NOT NULL) OR (? AND i.loss_reason_id IS NOT NULL)) LIMIT 1",(r,n)->incident(r.getObject(1,UUID.class)),machine,exclude,ts(to),ts(from),state,reason);}
  void insertIncident(UUID id,UUID machine,Instant from,Instant to,String state,UUID reason,String comment,String author){jdbc.update("INSERT INTO analytics.manual_incident(id,machine_id,starts_at,ends_at,state_override,loss_reason_id,comment,author) VALUES(?,?,?,?,?,?,?,?)",id,machine,Timestamp.from(from),ts(to),state,reason,comment,author);}
  void updateIncident(UUID id,Instant from,Instant to,String state,UUID reason,String comment){jdbc.update("UPDATE analytics.manual_incident SET starts_at=?,ends_at=?,state_override=?,loss_reason_id=?,comment=?,updated_at=clock_timestamp() WHERE id=?",Timestamp.from(from),ts(to),state,reason,comment,id);}
  void close(UUID id,Instant to){jdbc.update("UPDATE analytics.manual_incident SET ends_at=?,updated_at=clock_timestamp() WHERE id=?",Timestamp.from(to),id);}
  void cancel(UUID id){jdbc.update("UPDATE analytics.manual_incident SET cancelled_at=COALESCE(cancelled_at,clock_timestamp()),updated_at=clock_timestamp() WHERE id=?",id);}
  void queue(UUID machine,Instant from,Instant to){jdbc.query("SELECT analytics.queue_incident_range(?,?,?)",r->null,machine,Timestamp.from(from),ts(to));}
  void saveKey(String principal,String operation,UUID machine,String key,String hash,UUID incident){jdbc.update("INSERT INTO analytics.manual_incident_idempotency(principal_id,operation,machine_id,idempotency_key,request_hash,incident_id) VALUES(?,?,?,?,?,?)",principal,operation,machine,key,hash,incident);}
  void lockIdempotency(String principal,String operation,String key){String scope=principal.length()+":"+principal+operation.length()+":"+operation+key.length()+":"+key;jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->r.getObject(1),scope);}
  record Key(String hash,UUID incident){}
  Key key(String principal,String operation,String key){return jdbc.query("SELECT request_hash,incident_id FROM analytics.manual_incident_idempotency WHERE principal_id=? AND operation=? AND idempotency_key=?",r->r.next()?new Key(r.getString(1),r.getObject(2,UUID.class)):null,principal,operation,key);}
  List<Incident> page(UUID machine,UUID enterprise,UUID line,Instant from,Instant to,String effect,UUID reason,Boolean ongoing,boolean cancelled,int limit,String cursor){
    StringBuilder sql=new StringBuilder(INCIDENT_SQL+" WHERE (?::uuid IS NULL OR i.machine_id=?) AND (?::uuid IS NULL OR m.enterprise_id=?) AND (?::uuid IS NULL OR m.line_id=?) AND (?::timestamptz IS NULL OR COALESCE(i.ends_at,'infinity')>?) AND (?::timestamptz IS NULL OR i.starts_at<?) AND (?::text IS NULL OR CASE WHEN i.state_override IS NULL THEN 'LOSS_ANNOTATION' WHEN i.loss_reason_id IS NULL THEN 'STATE_OVERRIDE' ELSE 'STATE_OVERRIDE_WITH_REASON' END=?) AND (?::uuid IS NULL OR i.loss_reason_id=?) AND (?::boolean IS NULL OR (i.ends_at IS NULL)=?) AND (? OR i.cancelled_at IS NULL)");
    List<Object> args=new ArrayList<>(Arrays.asList(machine,machine,enterprise,enterprise,line,line,ts(from),ts(from),ts(to),ts(to),effect,effect,reason,reason,ongoing,ongoing,cancelled));
    if(cursor!=null){String[] c=decode(cursor);sql.append(" AND (i.created_at,i.id)<(?::timestamptz,?::uuid)");args.add(Timestamp.from(Instant.parse(c[0])));args.add(UUID.fromString(c[1]));}
    sql.append(" ORDER BY i.created_at DESC,i.id DESC LIMIT ?");args.add(limit+1);
    return jdbc.query(sql.toString(),IncidentRepository::mapIncident,args.toArray());
  }
  private static String[] decode(String c){try{return new String(Base64.getUrlDecoder().decode(c)).split("\\|",2);}catch(Exception e){throw new IllegalArgumentException("Invalid cursor");}}
  static String encode(Incident i){return Base64.getUrlEncoder().withoutPadding().encodeToString((i.createdAt()+"|"+i.id()).getBytes());}
  static final String INCIDENT_SQL="SELECT i.*,m.code machine_code,m.name machine_name,m.line_id,l.code line_code,l.name line_name,r.id reason_id,r.code reason_code,r.name reason_name,r.category reason_category,r.status reason_status,r.color reason_color,EXISTS(SELECT 1 FROM telemetry.recalculation_work w WHERE w.machine_id=i.machine_id AND w.status<>'IDLE' AND w.dirty_from<COALESCE(i.ends_at,clock_timestamp()) AND w.dirty_to>i.starts_at) recalculating FROM analytics.manual_incident i JOIN equipment.machine m ON m.id=i.machine_id LEFT JOIN equipment.production_line l ON l.id=m.line_id LEFT JOIN analytics.loss_reason r ON r.id=i.loss_reason_id";
  private static Incident mapIncident(ResultSet r,int n)throws SQLException{UUID rid=r.getObject("reason_id",UUID.class);return new Incident(r.getObject("id",UUID.class),r.getObject("machine_id",UUID.class),new MachineReference(r.getObject("machine_id",UUID.class),r.getString("machine_code"),r.getString("machine_name"),r.getObject("line_id",UUID.class),r.getString("line_code"),r.getString("line_name")),instant(r,"starts_at"),instant(r,"ends_at"),r.getString("state_override"),rid==null?null:new LossReasonSummary(rid,r.getString("reason_code"),r.getString("reason_name"),r.getString("reason_category"),r.getString("reason_status"),r.getString("reason_color")),r.getString("state_override")==null?"LOSS_ANNOTATION":r.getObject("loss_reason_id")==null?"STATE_OVERRIDE":"STATE_OVERRIDE_WITH_REASON",r.getString("comment"),r.getString("author"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"cancelled_at"),r.getBoolean("recalculating"));}
  static Instant instant(ResultSet r,String name)throws SQLException{Timestamp t=r.getTimestamp(name);return t==null?null:t.toInstant();}
  private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
}
