package ru.fkit.bi.analytics.machine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.machine.MachineModels.*;

@Repository
class MachineRepository {
  private final JdbcTemplate jdbc; private final ObjectMapper json;
  private final RowMapper<Machine> row=(r,n)->map(r);
  MachineRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
  private Machine map(ResultSet r)throws SQLException{try{return new Machine(r.getObject("id",UUID.class),r.getObject("enterprise_id",UUID.class),r.getObject("line_id",UUID.class),r.getString("code"),r.getString("name"),r.getString("machine_type"),r.getString("inventory_no"),r.getString("manufacturer"),r.getString("model"),r.getObject("commissioning_year")==null?null:r.getInt("commissioning_year"),r.getObject("nominal_rate")==null?null:r.getDouble("nominal_rate"),r.getString("production_unit_code"),(Integer)r.getObject("sort_order"),r.getString("status"),r.getTimestamp("coverage_from")==null?null:r.getTimestamp("coverage_from").toInstant(),json.readValue(r.getString("parameters"),new TypeReference<List<JsonNode>>(){}),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}catch(Exception e){throw new SQLException("Could not decode machine parameters",e);}}
  Optional<Machine> find(UUID id){return jdbc.query("select * from equipment.machine where id=?",row,id).stream().findFirst();}
  boolean enterpriseExists(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.enterprise where id=?)",Boolean.class,id));}
  boolean unitExists(String code){return code==null||Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.measurement_unit where code=? and status='ACTIVE')",Boolean.class,code));}
  boolean lineOwned(UUID e,UUID l){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.production_line where enterprise_id=? and id=?)",Boolean.class,e,l));}
  boolean lineExists(UUID line){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.production_line where id=?)",Boolean.class,line));}
  boolean lineActive(UUID line){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.production_line where id=? and status='ACTIVE')",Boolean.class,line));}
  boolean enterpriseActive(UUID machine){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.machine m join equipment.enterprise e on e.id=m.enterprise_id where m.id=? and e.status='ACTIVE')",Boolean.class,machine));}
  UUID lineEnterprise(UUID line){return jdbc.query("select enterprise_id from equipment.production_line where id=?",(r,n)->r.getObject(1,UUID.class),line).stream().findFirst().orElseThrow(()->new org.springframework.dao.EmptyResultDataAccessException(1));}
  boolean stateConfigurationExists(UUID machine){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('analytics.machine_state_config') is not null and exists(select 1 from analytics.machine_state_config where machine_id=?)",Boolean.class,machine));}
  List<Machine> page(UUID e,UUID line,String assignment,String status,String search,int p,int z){String q=pat(search);return jdbc.query("select * from equipment.machine where enterprise_id=? and (?::uuid is null or line_id=?) and (?='ALL' or (?='ASSIGNED' and line_id is not null) or (?='UNASSIGNED' and line_id is null)) and status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!' or lower(coalesce(inventory_no,'')) like lower(?) escape '!') order by coalesce(sort_order,2147483647),code,id limit ? offset ?",row,e,line,line,assignment,assignment,assignment,status,search,q,q,q,z,(long)p*z);}
  long count(UUID e,UUID line,String assignment,String status,String search){String q=pat(search);return jdbc.queryForObject("select count(*) from equipment.machine where enterprise_id=? and (?::uuid is null or line_id=?) and (?='ALL' or (?='ASSIGNED' and line_id is not null) or (?='UNASSIGNED' and line_id is null)) and status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!' or lower(coalesce(inventory_no,'')) like lower(?) escape '!')",Long.class,e,line,line,assignment,assignment,assignment,status,search,q,q,q);}
  Machine create(UUID id,UUID e,Create in){jdbc.update("insert into equipment.machine(id,enterprise_id,line_id,code,name,machine_type,inventory_no,manufacturer,model,commissioning_year,nominal_rate,production_unit_code,sort_order,parameters) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",id,e,in.lineId(),in.code(),in.name().trim(),in.machineType(),in.inventoryNo(),in.manufacturer(),in.model(),in.commissioningYear(),in.nominalRate(),in.productionUnit(),in.sortOrder(),write(in.parameters()));return find(id).orElseThrow();}
  void update(UUID id,Update in){jdbc.update("update equipment.machine set name=?,machine_type=?,inventory_no=?,manufacturer=?,model=?,commissioning_year=?,nominal_rate=?,production_unit_code=?,sort_order=?,parameters=?::jsonb,updated_at=current_timestamp where id=?",in.name().trim(),in.machineType(),in.inventoryNo(),in.manufacturer(),in.model(),in.commissioningYear(),in.nominalRate(),in.productionUnit(),in.sortOrder(),write(in.parameters()),id);}
  void status(UUID id,String status){jdbc.update("update equipment.machine set status=?,updated_at=current_timestamp where id=?",status,id);}
  int nextOrder(UUID line){Integer v=jdbc.queryForObject("select coalesce(max(sort_order)+10,10) from equipment.machine where line_id=?",Integer.class,line);return v==null?10:v;}
  void lock(UUID id){jdbc.query("select id from equipment.machine where id=? for update",(r,n)->r.getObject(1),id);}
  void lockLine(UUID id){jdbc.query("select id from equipment.production_line where id=? for update",(r,n)->r.getObject(1),id);}
  List<UUID> lockMachines(List<UUID> ids){return jdbc.query("select id from equipment.machine where id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") order by id for update",(r,n)->r.getObject(1,UUID.class),ids.toArray());}
  Map<UUID,Machine> findAll(List<UUID> ids){if(ids.isEmpty())return Map.of();Map<UUID,Machine> out=new HashMap<>();jdbc.query("select * from equipment.machine where id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",row,ids.toArray()).forEach(m->out.put(m.id(),m));return out;}
  void assign(List<UUID> ids,UUID line){int order=nextOrder(line);for(UUID id:ids){jdbc.update("update equipment.machine set line_id=?,sort_order=?,updated_at=current_timestamp where id=? and line_id is null",line,order,id);order+=10;}}
  private Object[] append(UUID line,List<UUID> ids){Object[] p=new Object[ids.size()+1];p[0]=line;for(int i=0;i<ids.size();i++)p[i+1]=ids.get(i);return p;}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private static String pat(String s){return s==null?null:"%"+s.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
