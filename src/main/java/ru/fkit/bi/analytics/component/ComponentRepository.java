package ru.fkit.bi.analytics.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.component.ComponentModels.*;

@Repository
class ComponentRepository {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final RowMapper<Component> row=(r,n)->map(r);
  ComponentRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
  private Component map(ResultSet r)throws SQLException{try{return new Component(r.getObject("id",UUID.class),r.getObject("machine_id",UUID.class),r.getObject("parent_component_id",UUID.class),r.getString("code"),r.getString("name"),r.getString("kind"),r.getInt("sort_order"),r.getString("status"),json.readValue(r.getString("parameters"),new TypeReference<List<JsonNode>>(){}),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}catch(Exception e){throw new SQLException("Could not decode component parameters",e);}}
  boolean machineExists(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.machine where id=?)",Boolean.class,id));}
  boolean unitExists(String code){return code==null||Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.measurement_unit where code=? and status='ACTIVE')",Boolean.class,code));}
  Optional<Component> find(UUID id){return jdbc.query("select * from equipment.component where id=?",row,id).stream().findFirst();}
  List<Component> list(UUID machine,String status){return jdbc.query("select * from equipment.component where machine_id=? and status=? order by parent_component_id nulls first,sort_order,code,id",row,machine,status);}
  Component create(UUID id,UUID machine,Create in){jdbc.update("insert into equipment.component(id,machine_id,parent_component_id,code,name,kind,sort_order,parameters) values (?,?,?,?,?,?,?,?::jsonb)",id,machine,in.parentComponentId(),in.code(),in.name().trim(),in.kind(),in.sortOrder(),write(in.parameters()));return find(id).orElseThrow();}
  void update(UUID id,Update in){jdbc.update("update equipment.component set name=?,kind=?,sort_order=?,parameters=?::jsonb,updated_at=current_timestamp where id=?",in.name().trim(),in.kind(),in.sortOrder(),write(in.parameters()),id);}
  void parent(UUID id,UUID parent){jdbc.update("update equipment.component set parent_component_id=?,updated_at=current_timestamp where id=?",parent,id);}
  void status(UUID id,String status){jdbc.update("update equipment.component set status=?,updated_at=current_timestamp where id=?",status,id);}
  void lockMachine(UUID id){jdbc.query("select id from equipment.machine where id=? for update",(r,n)->r.getObject(1),id);}
  boolean parentOwned(UUID machine,UUID parent){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.component where machine_id=? and id=?)",Boolean.class,machine,parent));}
  boolean createsCycle(UUID machine,UUID component,UUID parent){return Boolean.TRUE.equals(jdbc.queryForObject("with recursive descendants(id) as (select id from equipment.component where machine_id=? and id=? union all select c.id from equipment.component c join descendants d on c.parent_component_id=d.id where c.machine_id=?) select exists(select 1 from descendants where id=?)",Boolean.class,machine,component,machine,parent));}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException(e);}}
}
