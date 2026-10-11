package ru.fkit.bi.analytics.shift;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.shift.ShiftModels.Shift;

@Repository
public class ShiftRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Shift> ROW=(rs,n)->map(rs);
  ShiftRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
  private static Shift map(ResultSet rs)throws SQLException {
    return new Shift(rs.getObject("id",UUID.class),rs.getObject("enterprise_id",UUID.class),rs.getString("code"),rs.getString("name"),
        rs.getTime("start_local_time").toLocalTime(),rs.getTime("end_local_time").toLocalTime(),rs.getDate("valid_from").toLocalDate(),
        rs.getDate("valid_to")==null?null:rs.getDate("valid_to").toLocalDate(),rs.getInt("sort_order"),rs.getString("status"),
        false,rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
  }
  private Shift withUsage(Shift x){return new Shift(x.id(),x.enterpriseId(),x.code(),x.name(),x.startLocalTime(),x.endLocalTime(),x.validFrom(),x.validTo(),x.sortOrder(),x.status(),isUsed(x.id()),x.createdAt(),x.updatedAt());}
  public Optional<Shift> find(UUID id){return jdbc.query("select * from equipment.shift where id=?",ROW,id).stream().map(this::withUsage).findFirst();}
  public List<Shift> page(UUID enterpriseId,String status,LocalDate validOn,int page,int size){
    return jdbc.query("select * from equipment.shift where enterprise_id=? and status=? and (?::date is null or (valid_from<=? and (valid_to is null or ?<valid_to))) order by sort_order,code,id limit ? offset ?",
        ROW,enterpriseId,status,validOn,validOn,validOn,size,(long)page*size).stream().map(this::withUsage).toList();
  }
  public long count(UUID enterpriseId,String status,LocalDate validOn){
    return jdbc.queryForObject("select count(*) from equipment.shift where enterprise_id=? and status=? and (?::date is null or (valid_from<=? and (valid_to is null or ?<valid_to)))",Long.class,enterpriseId,status,validOn,validOn,validOn);
  }
  public List<Shift> all(UUID enterpriseId){return jdbc.query("select * from equipment.shift where enterprise_id=? order by code,id",ROW,enterpriseId);}
  public void lockEnterprise(UUID enterpriseId){jdbc.query("select id from equipment.enterprise where id=? for update",(rs,n)->rs.getObject(1),enterpriseId);}
  public boolean codeExists(UUID enterpriseId,String code,UUID except){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment.shift where enterprise_id=? and code=? and (?::uuid is null or id<>?))",Boolean.class,enterpriseId,code,except,except));}
  public Shift create(UUID id,UUID enterpriseId,String code,String name,LocalTime start,LocalTime end,LocalDate from,LocalDate to,int sort){
    jdbc.update("insert into equipment.shift(id,enterprise_id,code,name,start_local_time,end_local_time,valid_from,valid_to,sort_order) values (?,?,?,?,?,?,?,?,?)",id,enterpriseId,code,name,start,end,from,to,sort);
    return find(id).orElseThrow();
  }
  public void update(UUID id,String name,LocalTime start,LocalTime end,LocalDate from,LocalDate to,int sort){
    jdbc.update("update equipment.shift set name=?,start_local_time=?,end_local_time=?,valid_from=?,valid_to=?,sort_order=?,updated_at=current_timestamp where id=?",name,start,end,from,to,sort,id);
  }
  public void updateStatus(UUID id,String status){jdbc.update("update equipment.shift set status=?,updated_at=current_timestamp where id=?",status,id);}
  public boolean isUsed(UUID id){
    for(String table:List.of("analytics.machine_metric_bucket_1m","analytics.machine_loss_bucket_1m")){
      if(tableExists(table)){
        String[] p=table.split("\\.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+p[0]+"."+p[1]+" where shift_id=?)",Boolean.class,id)))return true;
      }
    }
    return false;
  }
  public boolean hasDateAtOrAfter(UUID id,LocalDate date){
    for(String table:List.of("analytics.machine_metric_bucket_1m","analytics.machine_loss_bucket_1m")){
      if(tableExists(table)){
        String[] p=table.split("\\.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+p[0]+"."+p[1]+" where shift_id=? and shift_date>=?)",Boolean.class,id,date)))return true;
      }
    }
    return false;
  }
  private boolean tableExists(String table){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null",Boolean.class,table));}
}
