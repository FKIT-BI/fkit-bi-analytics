package ru.fkit.bi.analytics.aggregation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.analytics.shift.ShiftCalendar;
import ru.fkit.bi.analytics.telemetry.TelemetryWorkRepository.WorkItem;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Rebuilds complete minute ranges into durable staging, then swaps them during publication. */
@Service
public class MinuteProjectionService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate stageTx;

  public MinuteProjectionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.stageTx = new TransactionTemplate(manager);
    this.stageTx.setTimeout(45);
  }

  public void stage(WorkItem item) {
    Instant from = item.dirtyFrom().truncatedTo(ChronoUnit.MINUTES);
    Instant to = ceilMinute(item.dirtyTo());
    if (!from.isBefore(to)) return;
    stageTx.executeWithoutResult(status -> {
      jdbc.execute("SET LOCAL statement_timeout='40s'");
      for (LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
           day.isBefore(to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)); day = day.plusDays(1)) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(telemetry.raw_partition_day_lock_key(?))",
            Boolean.class, java.sql.Date.valueOf(day));
        Integer active = jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_partition_registry WHERE partition_day=? AND status='ACTIVE'",
            Integer.class, java.sql.Date.valueOf(day));
        if (!Boolean.TRUE.equals(locked) || active == null || active != 1) {
          throw new IllegalStateException("RAW_PARTITION_BARRIER_BUSY");
        }
      }
      jdbc.update("DELETE FROM analytics.staging_signal_numeric_rollup_1m WHERE work_machine_id=? AND work_generation=?", item.machineId(), item.generation());
      jdbc.update("DELETE FROM analytics.staging_machine_loss_bucket_1m WHERE machine_id=? AND work_generation=?", item.machineId(), item.generation());
      jdbc.update("DELETE FROM analytics.staging_machine_metric_bucket_1m WHERE machine_id=? AND work_generation=?", item.machineId(), item.generation());
      jdbc.update("DELETE FROM analytics.staging_machine_effective_state_interval WHERE machine_id=? AND work_generation=?", item.machineId(), item.generation());
      stageNumeric(item, from, to);
      stageEffective(item);
      stageMachine(item, from, to);
      applyShiftAttribution(item);
    });
  }

  public void publish(WorkItem item) {
    Instant from = item.dirtyFrom().truncatedTo(ChronoUnit.MINUTES);
    Instant to = ceilMinute(item.dirtyTo());
    if (!from.isBefore(to)) return;
    Timestamp start = Timestamp.from(from), end = Timestamp.from(to);
    // Replace the reconstructed timeline from its stable anchor through the final staged interval.
    jdbc.update("DELETE FROM analytics.machine_state_interval i USING (SELECT min(effective_from) first_from,max(effective_from) last_from "
        + "FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?) s "
        + "WHERE i.machine_id=? AND (i.effective_from>=s.first_from OR COALESCE(i.effective_to,'infinity'::timestamptz)>s.first_from)",
        item.machineId(),item.generation(),item.machineId());
    jdbc.update("INSERT INTO analytics.machine_state_interval(machine_id,effective_from,effective_to,state,detected_at,calculated_at) "
        + "SELECT machine_id,effective_from,effective_to,state,detected_at,clock_timestamp() FROM analytics.staging_machine_state_interval "
        + "WHERE machine_id=? AND work_generation=? ORDER BY effective_from",item.machineId(),item.generation());
    jdbc.update("DELETE FROM analytics.machine_effective_state_interval e USING (SELECT min(effective_from) first_from FROM analytics.staging_machine_effective_state_interval WHERE machine_id=? AND work_generation=?) s "
        + "WHERE e.machine_id=? AND (e.effective_from>=s.first_from OR COALESCE(e.effective_to,'infinity'::timestamptz)>s.first_from)",item.machineId(),item.generation(),item.machineId());
    jdbc.update("INSERT INTO analytics.machine_effective_state_interval(machine_id,effective_from,effective_to,effective_state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id,calculated_at) "
        + "SELECT machine_id,effective_from,effective_to,effective_state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id,clock_timestamp() "
        + "FROM analytics.staging_machine_effective_state_interval WHERE machine_id=? AND work_generation=? ORDER BY effective_from",item.machineId(),item.generation());
    jdbc.update("UPDATE analytics.machine_current_state c SET state=s.state,state_since=s.effective_from,matched_rule_id=(SELECT (r->>'id')::uuid FROM analytics.machine_state_config cfg CROSS JOIN LATERAL jsonb_array_elements(cfg.configuration->'rules') r WHERE cfg.machine_id=c.machine_id AND r->>'targetState'=s.state ORDER BY (r->>'priority')::integer DESC LIMIT 1),calculated_at=clock_timestamp() FROM (SELECT state,effective_from FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=? ORDER BY effective_from DESC LIMIT 1) s WHERE c.machine_id=?",
        item.machineId(),item.generation(),item.machineId());
    jdbc.update("DELETE FROM analytics.signal_numeric_rollup_1m r USING equipment.telemetry_signal s "
        + "WHERE r.signal_id=s.id AND s.machine_id=? AND r.bucket_start>=? AND r.bucket_start<?", item.machineId(), start, end);
    jdbc.update("INSERT INTO analytics.signal_numeric_rollup_1m(signal_id,bucket_start,sample_count,usable_count,good_count,uncertain_count,bad_count,out_of_range_count,min_value,max_value,sum_value,first_value,last_value,first_observed_at,last_observed_at,delta_value,reset_count,calculated_at) "
        + "SELECT signal_id,bucket_start,sample_count,usable_count,good_count,uncertain_count,bad_count,out_of_range_count,min_value,max_value,sum_value,first_value,last_value,first_observed_at,last_observed_at,delta_value,reset_count,clock_timestamp() "
        + "FROM analytics.staging_signal_numeric_rollup_1m WHERE work_machine_id=? AND work_generation=?", item.machineId(), item.generation());
    jdbc.update("DELETE FROM analytics.machine_loss_bucket_1m WHERE machine_id=? AND bucket_start>=? AND bucket_start<?", item.machineId(), start, end);
    jdbc.update("INSERT INTO analytics.machine_loss_bucket_1m(machine_id,bucket_start,shift_id,shift_date,state,loss_reason_id,duration_ms,calculated_at) "
        + "SELECT machine_id,bucket_start,shift_id,shift_date,state,loss_reason_id,duration_ms,clock_timestamp() "
        + "FROM analytics.staging_machine_loss_bucket_1m WHERE machine_id=? AND work_generation=?", item.machineId(), item.generation());
    jdbc.update("DELETE FROM analytics.machine_metric_bucket_1m WHERE machine_id=? AND bucket_start>=? AND bucket_start<?", item.machineId(), start, end);
    jdbc.update("INSERT INTO analytics.machine_metric_bucket_1m(machine_id,bucket_start,shift_id,shift_date,covered_duration_ms,run_duration_ms,idle_duration_ms,down_duration_ms,setup_duration_ms,planned_stop_duration_ms,effective_offline_duration_ms,underlying_offline_duration_ms,produced_quantity,scrap_quantity,stop_count,failure_count,recovery_count,calculated_at) "
        + "SELECT machine_id,bucket_start,shift_id,shift_date,covered_duration_ms,run_duration_ms,idle_duration_ms,down_duration_ms,setup_duration_ms,planned_stop_duration_ms,effective_offline_duration_ms,underlying_offline_duration_ms,produced_quantity,scrap_quantity,stop_count,failure_count,recovery_count,clock_timestamp() "
        + "FROM analytics.staging_machine_metric_bucket_1m WHERE machine_id=? AND work_generation=?", item.machineId(), item.generation());
    jdbc.update("DELETE FROM analytics.staging_signal_numeric_rollup_1m WHERE work_machine_id=? AND work_generation=?",item.machineId(),item.generation());
    jdbc.update("DELETE FROM analytics.staging_machine_loss_bucket_1m WHERE machine_id=? AND work_generation=?",item.machineId(),item.generation());
    jdbc.update("DELETE FROM analytics.staging_machine_metric_bucket_1m WHERE machine_id=? AND work_generation=?",item.machineId(),item.generation());
    jdbc.update("DELETE FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?",item.machineId(),item.generation());
    jdbc.update("DELETE FROM analytics.staging_machine_effective_state_interval WHERE machine_id=? AND work_generation=?",item.machineId(),item.generation());
  }

  private void stageNumeric(WorkItem item, Instant from, Instant to) {
    String sql = "WITH raw AS (SELECT r.signal_id,date_trunc('minute',r.observed_at) bucket_start,r.observed_at,r.ingest_batch_id,r.ordinal, "
        + "COALESCE(r.value_double,r.value_long::double precision) value,r.source_quality,r.validation_flags,s.signal_kind "
        + "FROM telemetry.raw_measurement r JOIN equipment.telemetry_signal s ON s.id=r.signal_id "
        + "JOIN analytics.machine_current_state c ON c.machine_id=s.machine_id "
        + "WHERE s.machine_id=? AND s.value_type IN ('DOUBLE','LONG') AND s.signal_kind IN ('GAUGE','COUNTER') AND r.observed_at>=? AND r.observed_at<? "
        + "AND date_trunc('minute',r.observed_at)+interval '1 minute'<=date_trunc('minute',GREATEST(c.data_through,?::timestamptz))), "
        + "quality AS (SELECT signal_id,bucket_start,count(*) sample_count,count(*) FILTER(WHERE source_quality=0 AND (validation_flags&1)=0) usable_count, "
        + "count(*) FILTER(WHERE source_quality=0) good_count,count(*) FILTER(WHERE source_quality=1) uncertain_count,count(*) FILTER(WHERE source_quality=2) bad_count, "
        + "count(*) FILTER(WHERE (validation_flags&1)<>0) out_of_range_count FROM raw GROUP BY signal_id,bucket_start), "
        + "usable AS (SELECT r.*, (SELECT COALESCE(p.value_double,p.value_long::double precision) FROM telemetry.raw_measurement p "
        + "WHERE p.signal_id=r.signal_id AND p.observed_at<=r.observed_at AND (p.observed_at<r.observed_at OR p.ingest_batch_id<r.ingest_batch_id OR (p.ingest_batch_id=r.ingest_batch_id AND p.ordinal<r.ordinal)) "
        + "AND p.source_quality=0 AND (p.validation_flags&1)=0 ORDER BY p.observed_at DESC,p.ingest_batch_id DESC,p.ordinal DESC LIMIT 1) previous "
        + "FROM raw r WHERE r.source_quality=0 AND (r.validation_flags&1)=0), "
        + "values_by_minute AS (SELECT signal_id,bucket_start,min(value) FILTER(WHERE signal_kind='GAUGE') min_value,max(value) FILTER(WHERE signal_kind='GAUGE') max_value, "
        + "sum(value) FILTER(WHERE signal_kind='GAUGE') sum_value, "
        + "(array_agg(value ORDER BY observed_at,ingest_batch_id,ordinal))[1] first_value,(array_agg(value ORDER BY observed_at DESC,ingest_batch_id DESC,ordinal DESC))[1] last_value, "
        + "min(observed_at) first_observed_at,max(observed_at) last_observed_at, "
        + "sum(CASE WHEN signal_kind='COUNTER' AND previous IS NULL THEN 0 WHEN signal_kind='COUNTER' AND value>=previous THEN value-previous WHEN signal_kind='COUNTER' THEN value ELSE 0 END) FILTER(WHERE signal_kind='COUNTER') delta_value, "
        + "count(*) FILTER(WHERE signal_kind='COUNTER' AND previous IS NOT NULL AND value<previous) reset_count "
        + "FROM usable GROUP BY signal_id,bucket_start) "
        + "INSERT INTO analytics.staging_signal_numeric_rollup_1m(signal_id,bucket_start,sample_count,usable_count,good_count,uncertain_count,bad_count,out_of_range_count,min_value,max_value,sum_value,first_value,last_value,first_observed_at,last_observed_at,delta_value,reset_count,calculated_at,work_machine_id,work_generation) "
        + "SELECT q.signal_id,q.bucket_start,q.sample_count,q.usable_count,q.good_count,q.uncertain_count,q.bad_count,q.out_of_range_count,v.min_value,v.max_value,v.sum_value, "
        + "CASE WHEN s.signal_kind='COUNTER' THEN v.first_value ELSE v.first_value END,v.last_value,v.first_observed_at,v.last_observed_at, "
        + "CASE WHEN s.signal_kind='COUNTER' AND q.usable_count>0 THEN COALESCE(v.delta_value,0) END,CASE WHEN s.signal_kind='COUNTER' THEN COALESCE(v.reset_count,0) END,clock_timestamp(),?,? "
        + "FROM quality q JOIN equipment.telemetry_signal s ON s.id=q.signal_id LEFT JOIN values_by_minute v USING(signal_id,bucket_start)";
    jdbc.update(sql, item.machineId(), Timestamp.from(from), Timestamp.from(to), Timestamp.from(item.dirtyTo()), item.machineId(), item.generation());
  }

  private void stageEffective(WorkItem item) {
    String sql = "WITH source AS (SELECT machine_id,effective_from,effective_to,state FROM analytics.staging_machine_state_interval WHERE machine_id=? AND work_generation=?), "
        + "bounds AS (SELECT s.machine_id,s.effective_from source_from,s.effective_to source_to,s.state, s.effective_from boundary FROM source s "
        + "UNION SELECT s.machine_id,s.effective_from,s.effective_to,s.state,s.effective_to FROM source s WHERE s.effective_to IS NOT NULL "
        + "UNION SELECT s.machine_id,s.effective_from,s.effective_to,s.state,GREATEST(s.effective_from,i.starts_at) FROM source s JOIN analytics.manual_incident i ON i.machine_id=s.machine_id "
        + "WHERE i.cancelled_at IS NULL AND i.starts_at> s.effective_from AND i.starts_at<COALESCE(s.effective_to,'infinity'::timestamptz) "
        + "UNION SELECT s.machine_id,s.effective_from,s.effective_to,s.state,LEAST(i.ends_at,COALESCE(s.effective_to,i.ends_at)) FROM source s JOIN analytics.manual_incident i ON i.machine_id=s.machine_id "
        + "WHERE i.cancelled_at IS NULL AND i.ends_at IS NOT NULL AND i.ends_at>s.effective_from AND i.ends_at<COALESCE(s.effective_to,'infinity'::timestamptz)), "
        + "segments AS (SELECT machine_id,source_from,source_to,state,boundary effective_from,LEAD(boundary) OVER(PARTITION BY machine_id,source_from ORDER BY boundary) effective_to FROM (SELECT DISTINCT * FROM bounds) b), "
        + "applied AS (SELECT s.*,st.id state_id,rs.id reason_incident,rs.loss_reason_id,COALESCE(st.state_override,s.state) effective_state "
        + "FROM segments s LEFT JOIN LATERAL (SELECT i.id,i.state_override FROM analytics.manual_incident i WHERE i.machine_id=s.machine_id AND i.cancelled_at IS NULL "
        + "AND i.state_override IS NOT NULL AND i.starts_at<=s.effective_from AND COALESCE(i.ends_at,'infinity'::timestamptz)>s.effective_from LIMIT 1) st ON true "
        + "LEFT JOIN LATERAL (SELECT i.id,i.loss_reason_id FROM analytics.manual_incident i WHERE i.machine_id=s.machine_id AND i.cancelled_at IS NULL "
        + "AND i.loss_reason_id IS NOT NULL AND i.starts_at<=s.effective_from AND COALESCE(i.ends_at,'infinity'::timestamptz)>s.effective_from LIMIT 1) rs ON true) "
        + "INSERT INTO analytics.staging_machine_effective_state_interval(machine_id,effective_from,effective_to,effective_state,calculated_state,state_source,state_incident_id,loss_reason_id,reason_incident_id,calculated_at,work_generation) "
        + "SELECT machine_id,effective_from,effective_to,effective_state,state,CASE WHEN state_id IS NULL THEN 'CALCULATED' ELSE 'MANUAL' END,state_id, "
        + "CASE WHEN effective_state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') THEN loss_reason_id END, "
        + "CASE WHEN effective_state IN ('IDLE','DOWN','SETUP','PLANNED_STOP') THEN reason_incident END,clock_timestamp(),? FROM applied WHERE effective_from IS NOT NULL AND (source_to IS NULL OR effective_from<source_to)";
    jdbc.update(sql,item.machineId(),item.generation(),item.generation());
  }

  private void stageMachine(WorkItem item, Instant from, Instant to) {
    String metrics = "WITH machine AS (SELECT m.id,m.coverage_from,GREATEST(cs.data_through,?::timestamptz) data_through FROM equipment.machine m "
        + "LEFT JOIN analytics.machine_current_state cs ON cs.machine_id=m.id WHERE m.id=?), "
        + "minutes AS (SELECT g bucket_start FROM machine CROSS JOIN LATERAL generate_series(date_trunc('minute',GREATEST(machine.coverage_from,?::timestamptz)),?::timestamptz-interval '1 minute',interval '1 minute') g "
        + "WHERE machine.data_through IS NOT NULL AND g+interval '1 minute'<=machine.data_through), "
        + "effective AS (SELECT i.machine_id,i.effective_from,i.effective_to,i.effective_state state,i.calculated_state,i.loss_reason_id FROM analytics.staging_machine_effective_state_interval i WHERE i.machine_id=? AND i.work_generation=?), "
        + "edges AS (SELECT machine_id,effective_from,state,LAG(state) OVER(PARTITION BY machine_id ORDER BY effective_from) previous_state FROM effective), "
        + "transitions AS (SELECT machine_id,date_trunc('minute',effective_from) bucket_start, "
        + "count(*) FILTER(WHERE previous_state='RUN' AND state IN ('IDLE','DOWN')) stop_count, "
        + "count(*) FILTER(WHERE previous_state NOT IN ('DOWN','OFFLINE') AND state='DOWN') failure_count, "
        + "count(*) FILTER(WHERE previous_state='DOWN' AND state NOT IN ('DOWN','OFFLINE')) recovery_count "
        + "FROM edges GROUP BY machine_id,date_trunc('minute',effective_from)), "
        + "durations AS (SELECT m.id machine_id,t.bucket_start,COALESCE(e.state,'OFFLINE') state,e.calculated_state,e.loss_reason_id, "
        + "GREATEST(0,(EXTRACT(EPOCH FROM (LEAST(COALESCE(e.effective_to,m.data_through),t.bucket_start+interval '1 minute')-GREATEST(e.effective_from,t.bucket_start)))*1000)::bigint) ms "
        + "FROM machine m JOIN minutes t ON true LEFT JOIN effective e ON e.effective_from<t.bucket_start+interval '1 minute' AND COALESCE(e.effective_to,m.data_through)>t.bucket_start), "
        + "facts AS (SELECT d.machine_id,d.bucket_start,sum(ms) FILTER(WHERE state='RUN') run_ms,sum(ms) FILTER(WHERE state='IDLE') idle_ms,sum(ms) FILTER(WHERE state='DOWN') down_ms, "
        + "sum(ms) FILTER(WHERE state='SETUP') setup_ms,sum(ms) FILTER(WHERE state='PLANNED_STOP') planned_ms,sum(ms) FILTER(WHERE state='OFFLINE') effective_offline_ms, "
        + "sum(ms) FILTER(WHERE calculated_state='OFFLINE') underlying_offline_ms FROM durations d GROUP BY d.machine_id,d.bucket_start) "
        + "INSERT INTO analytics.staging_machine_metric_bucket_1m(machine_id,bucket_start,shift_id,shift_date,covered_duration_ms,run_duration_ms,idle_duration_ms,down_duration_ms,setup_duration_ms,planned_stop_duration_ms,effective_offline_duration_ms,underlying_offline_duration_ms,produced_quantity,scrap_quantity,stop_count,failure_count,recovery_count,calculated_at,work_generation) "
        + "SELECT f.machine_id,f.bucket_start,NULL,NULL,LEAST(60000,GREATEST(0,(EXTRACT(EPOCH FROM (LEAST(m.data_through,f.bucket_start+interval '1 minute')-GREATEST(m.coverage_from,f.bucket_start)))*1000)::bigint)), "
        + "COALESCE(run_ms,0),COALESCE(idle_ms,0),COALESCE(down_ms,0),COALESCE(setup_ms,0),COALESCE(planned_ms,0),COALESCE(effective_offline_ms,0),COALESCE(underlying_offline_ms,0), "
        + "(SELECT sum(r.delta_value) FROM analytics.staging_signal_numeric_rollup_1m r JOIN equipment.telemetry_signal s ON s.id=r.signal_id WHERE s.machine_id=f.machine_id AND s.semantic_code='PRODUCTION_COUNT' AND s.signal_kind='COUNTER' AND r.work_machine_id=? AND r.work_generation=? AND r.bucket_start=f.bucket_start), "
        + "(SELECT sum(r.delta_value) FROM analytics.staging_signal_numeric_rollup_1m r JOIN equipment.telemetry_signal s ON s.id=r.signal_id WHERE s.machine_id=f.machine_id AND s.semantic_code='SCRAP_COUNT' AND s.signal_kind='COUNTER' AND r.work_machine_id=? AND r.work_generation=? AND r.bucket_start=f.bucket_start), "
        + "COALESCE(t.stop_count,0),COALESCE(t.failure_count,0),COALESCE(t.recovery_count,0),clock_timestamp(),? FROM facts f JOIN machine m ON m.id=f.machine_id "
        + "LEFT JOIN transitions t ON t.machine_id=f.machine_id AND t.bucket_start=f.bucket_start";
    jdbc.update(metrics, Timestamp.from(item.dirtyTo()),item.machineId(), Timestamp.from(from), Timestamp.from(to), item.machineId(), item.generation(),
        item.machineId(), item.generation(), item.machineId(), item.generation(), item.generation());

    String losses = "WITH timeline AS (SELECT machine_id,effective_from,effective_to,effective_state state,loss_reason_id FROM analytics.staging_machine_effective_state_interval WHERE machine_id=? AND work_generation=?), "
        + "context AS (SELECT machine_id,GREATEST(data_through,?::timestamptz) data_through FROM analytics.machine_current_state WHERE machine_id=?), "
        + "minutes AS (SELECT g bucket_start FROM generate_series(?::timestamptz,?::timestamptz-interval '1 minute',interval '1 minute') g), "
        + "overlap AS (SELECT i.machine_id,m.bucket_start,i.state,i.loss_reason_id, "
        + "GREATEST(0,(EXTRACT(EPOCH FROM (LEAST(COALESCE(i.effective_to,cs.data_through),m.bucket_start+interval '1 minute')-GREATEST(i.effective_from,m.bucket_start)))*1000)::bigint) duration_ms "
        + "FROM timeline i JOIN minutes m ON i.effective_from<m.bucket_start+interval '1 minute' "
        + "JOIN context cs ON cs.machine_id=i.machine_id "
        + "WHERE COALESCE(i.effective_to,cs.data_through)>m.bucket_start AND i.state IN ('IDLE','DOWN','SETUP','PLANNED_STOP')) "
        + "INSERT INTO analytics.staging_machine_loss_bucket_1m(machine_id,bucket_start,shift_id,shift_date,state,loss_reason_id,duration_ms,calculated_at,work_generation) "
        + "SELECT machine_id,bucket_start,NULL,NULL,state,loss_reason_id,sum(duration_ms),clock_timestamp(),? FROM overlap WHERE duration_ms>0 "
        + "GROUP BY machine_id,bucket_start,state,loss_reason_id";
    jdbc.update(losses, item.machineId(), item.generation(),Timestamp.from(item.dirtyTo()),item.machineId(), Timestamp.from(from), Timestamp.from(to), item.generation());
  }

  private void applyShiftAttribution(WorkItem item) {
    MachineShift machine = jdbc.query("SELECT e.timezone FROM equipment.machine m JOIN equipment.enterprise e ON e.id=m.enterprise_id WHERE m.id=?",
        (rs, i) -> new MachineShift(ZoneId.of(rs.getString(1))), item.machineId()).stream().findFirst().orElse(null);
    if (machine == null) return;
    var definitions = jdbc.query("SELECT id,start_local_time,end_local_time,valid_from,valid_to FROM equipment.shift WHERE enterprise_id=(SELECT enterprise_id FROM equipment.machine WHERE id=?)",
        (rs, i) -> new ShiftCalendar.Definition(rs.getObject("id", java.util.UUID.class),
            rs.getTime("start_local_time").toLocalTime(), rs.getTime("end_local_time").toLocalTime(),
            rs.getDate("valid_from").toLocalDate(), rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate()), item.machineId());
    var buckets = jdbc.query("SELECT bucket_start FROM analytics.staging_machine_metric_bucket_1m WHERE machine_id=? AND work_generation=? ORDER BY bucket_start",
        (rs, i) -> rs.getTimestamp(1).toInstant(), item.machineId(), item.generation());
    for (Instant bucket : buckets) {
      var occurrence = ShiftCalendar.resolve(bucket, machine.zone(), definitions);
      if (occurrence == null) continue;
      var shiftDate = java.sql.Date.valueOf(occurrence.shiftDate());
      var shiftId = occurrence.shiftId();
      jdbc.update("UPDATE analytics.staging_machine_metric_bucket_1m SET shift_id=?,shift_date=? WHERE machine_id=? AND bucket_start=? AND work_generation=?",
          shiftId, shiftDate, item.machineId(), Timestamp.from(bucket), item.generation());
      jdbc.update("UPDATE analytics.staging_machine_loss_bucket_1m SET shift_id=?,shift_date=? WHERE machine_id=? AND bucket_start=? AND work_generation=?",
          shiftId, shiftDate, item.machineId(), Timestamp.from(bucket), item.generation());
    }
  }

  private static Instant ceilMinute(Instant value) {
    Instant floor = value.truncatedTo(ChronoUnit.MINUTES);
    return floor.equals(value) ? value : floor.plus(1, ChronoUnit.MINUTES);
  }
  private record MachineShift(ZoneId zone) { }
}
