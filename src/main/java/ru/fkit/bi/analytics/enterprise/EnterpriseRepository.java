package ru.fkit.bi.analytics.enterprise;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.Enterprise;

@Repository
public class EnterpriseRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Enterprise> ROW = (rs, n) -> map(rs);
  EnterpriseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  private static Enterprise map(ResultSet rs) throws SQLException {
    return new Enterprise(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
        rs.getString("timezone"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
  public Optional<Enterprise> find(UUID id) {
    return jdbc.query("select * from equipment.enterprise where id=?", ROW, id).stream().findFirst();
  }
  public List<Enterprise> page(String status, String search, int page, int size) {
    String pattern = search == null ? null : likeLiteral(search);
    return jdbc.query("select * from equipment.enterprise where status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!') order by code,id limit ? offset ?",
        ROW, status, search, pattern, pattern, size, (long) page*size);
  }
  public long count(String status, String search) {
    String pattern = search == null ? null : likeLiteral(search);
    return jdbc.queryForObject("select count(*) from equipment.enterprise where status=? and (?::text is null or lower(code) like lower(?) escape '!' or lower(name) like lower(?) escape '!')",
        Long.class, status, search, pattern, pattern);
  }
  public Enterprise create(UUID id, String code, String name, String timezone) {
    jdbc.update("insert into equipment.enterprise(id,code,name,timezone) values (?,?,?,?)", id,code,name,timezone);
    return find(id).orElseThrow();
  }
  public void update(UUID id, String name, String timezone) {
    jdbc.update("update equipment.enterprise set name=?, timezone=?, updated_at=current_timestamp where id=?", name,timezone,id);
  }
  public void updateStatus(UUID id, String status) {
    jdbc.update("update equipment.enterprise set status=?, updated_at=current_timestamp where id=?", status,id);
  }
  public void lock(UUID id) { jdbc.query("select id from equipment.enterprise where id=? for update", (rs,n)->rs.getObject(1), id); }
  public boolean hasTemporalData(UUID id) {
    return hasSignalUsage(id)
        || hasMachineRows("analytics.machine_metric_bucket_1m", id)
        || hasMachineRows("analytics.machine_loss_bucket_1m", id)
        || hasMachineRows("incident.incident", id)
        || hasRow("generator.generation_run", "enterprise_id", id);
  }
  private boolean hasSignalUsage(UUID id) {
    if (!tableExists("telemetry.signal_usage") || !tableExists("equipment.telemetry_signal") || !tableExists("equipment.machine")) return false;
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from telemetry.signal_usage u join equipment.telemetry_signal s on s.id=u.signal_id join equipment.machine m on m.id=s.machine_id where m.enterprise_id=?)", Boolean.class, id));
  }
  private boolean hasMachineRows(String table, UUID id) {
    if (!tableExists(table) || !tableExists("equipment.machine")) return false;
    String[] p=table.split("\\.");
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+p[0]+"."+p[1]+" t join equipment.machine m on m.id=t.machine_id where m.enterprise_id=?)", Boolean.class, id));
  }
  private boolean hasRow(String table, String ownerColumn, UUID id) {
    if (!tableExists(table)) return false;
    String[] p=table.split("\\.");
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+p[0]+"."+p[1]+" where "+ownerColumn+"=?)", Boolean.class,id));
  }
  private boolean tableExists(String table) { return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table)); }
  private static String likeLiteral(String value) { return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
}
