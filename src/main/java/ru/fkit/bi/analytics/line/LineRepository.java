package ru.fkit.bi.analytics.line;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.line.LineModels.Line;

@Repository
class LineRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Line> ROW=(r,n)->map(r);
  LineRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
  private static Line map(ResultSet r)throws SQLException{return new Line(r.getObject("id",UUID.class),r.getObject("enterprise_id",UUID.class),r.getString("code"),r.getString("name"),r.getInt("sort_order"),r.getString("status"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
  Optional<Line> find(UUID id){return jdbc.query("select * from equipment.production_line where id=?",ROW,id).stream().findFirst();}
  boolean enterpriseExists(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.enterprise where id=?)",Boolean.class,id));}
  List<Line> page(UUID e,String status,String search,int page,int size){String p=pattern(search);return jdbc.query("select * from equipment.production_line where enterprise_id=? and status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!') order by sort_order,code,id limit ? offset ?",ROW,e,status,search,p,p,size,(long)page*size);}
  long count(UUID e,String status,String search){String p=pattern(search);return jdbc.queryForObject("select count(*) from equipment.production_line where enterprise_id=? and status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!')",Long.class,e,status,search,p,p);}
  int nextOrder(UUID e){Integer v=jdbc.queryForObject("select coalesce(max(sort_order)+10,10) from equipment.production_line where enterprise_id=?",Integer.class,e);return v==null?10:v;}
  Line create(UUID id,UUID e,String c,String n,int o){jdbc.update("insert into equipment.production_line(id,enterprise_id,code,name,sort_order) values (?,?,?,?,?)",id,e,c,n,o);return find(id).orElseThrow();}
  void update(UUID id,String n,int o){jdbc.update("update equipment.production_line set name=?,sort_order=?,updated_at=current_timestamp where id=?",n,o,id);}
  void status(UUID id,String s){jdbc.update("update equipment.production_line set status=?,updated_at=current_timestamp where id=?",s,id);}
  private static String pattern(String s){return s==null?null:"%"+s.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
  void lock(UUID id){jdbc.query("select id from equipment.production_line where id=? for update",(r,n)->r.getObject(1),id);}
}
