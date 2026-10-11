--liquibase formatted sql

--changeset fkit-bi:010-001-machine-state-projection
CREATE TABLE analytics.machine_current_state (
    machine_id UUID PRIMARY KEY REFERENCES equipment.machine(id),
    coverage_from TIMESTAMPTZ(3) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUN','IDLE','DOWN','SETUP','PLANNED_STOP','OFFLINE')),
    state_since TIMESTAMPTZ(3) NOT NULL,
    matched_rule_id UUID,
    evaluation_quality VARCHAR(16) NOT NULL CHECK (evaluation_quality IN ('COMPLETE','DEGRADED')),
    last_telemetry_observed_at TIMESTAMPTZ(3),
    data_through TIMESTAMPTZ(3) NOT NULL,
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    recalculating BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE analytics.machine_state_interval (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    effective_from TIMESTAMPTZ(3) NOT NULL,
    effective_to TIMESTAMPTZ(3),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUN','IDLE','DOWN','SETUP','PLANNED_STOP','OFFLINE')),
    detected_at TIMESTAMPTZ(3) NOT NULL,
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(machine_id,effective_from),
    CONSTRAINT ck_machine_state_interval_positive CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE UNIQUE INDEX uq_machine_state_interval_open ON analytics.machine_state_interval(machine_id) WHERE effective_to IS NULL;
CREATE INDEX ix_machine_state_interval_time ON analytics.machine_state_interval(machine_id,effective_from,effective_to);

CREATE TABLE analytics.machine_state_rule_runtime (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    rule_id UUID NOT NULL,
    enter_true_since TIMESTAMPTZ(3),
    exit_true_since TIMESTAMPTZ(3),
    PRIMARY KEY(machine_id,rule_id)
);

CREATE TABLE analytics.machine_evaluation_deadline (
    machine_id UUID PRIMARY KEY REFERENCES equipment.machine(id),
    deadline_at TIMESTAMPTZ(3) NOT NULL,
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('SIGNAL_STALE','RULE_ACTIVATION','RULE_RELEASE','MACHINE_OFFLINE')),
    lease_owner UUID,
    lease_until TIMESTAMPTZ(3),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_machine_deadline_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_machine_deadline_due ON analytics.machine_evaluation_deadline(deadline_at,lease_until);

--changeset fkit-bi:010-002-enqueue-machine-deadlines splitStatements:false
CREATE FUNCTION analytics.enqueue_due_machine_deadlines(p_owner UUID,p_limit INTEGER DEFAULT 100)
RETURNS INTEGER LANGUAGE plpgsql
SET search_path = pg_catalog, analytics, telemetry
AS $$
DECLARE v_count INTEGER;
BEGIN
    WITH picked AS (
      SELECT machine_id,deadline_at FROM analytics.machine_evaluation_deadline
      WHERE deadline_at <= clock_timestamp() AND (lease_until IS NULL OR lease_until < clock_timestamp())
      ORDER BY deadline_at,machine_id FOR UPDATE SKIP LOCKED LIMIT p_limit
    ), leased AS (
      UPDATE analytics.machine_evaluation_deadline d
      SET lease_owner=p_owner,lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
      FROM picked p WHERE d.machine_id=p.machine_id RETURNING d.machine_id,p.deadline_at
    ), queued AS (
      INSERT INTO telemetry.recalculation_work(machine_id,dirty_from,dirty_to)
      SELECT machine_id,deadline_at,deadline_at+interval '1 millisecond' FROM leased
      ON CONFLICT(machine_id) DO UPDATE SET
        dirty_from=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_from
                        ELSE LEAST(telemetry.recalculation_work.dirty_from,EXCLUDED.dirty_from) END,
        dirty_to=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_to
                      ELSE GREATEST(telemetry.recalculation_work.dirty_to,EXCLUDED.dirty_to) END,
        generation=telemetry.recalculation_work.generation+1,
        status=CASE WHEN telemetry.recalculation_work.lease_owner IS NULL THEN 'PENDING'
                    ELSE telemetry.recalculation_work.status END,
        updated_at=clock_timestamp()
      RETURNING machine_id
    ) SELECT count(*) INTO v_count FROM queued;
    RETURN v_count;
END $$;

--changeset fkit-bi:010-003-record-machine-state splitStatements:false
CREATE FUNCTION analytics.record_machine_state(p_machine UUID,p_state VARCHAR,p_effective TIMESTAMPTZ,
    p_detected TIMESTAMPTZ,p_rule UUID,p_quality VARCHAR,p_last_telemetry TIMESTAMPTZ,
    p_data_through TIMESTAMPTZ,p_coverage_from TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql
SET search_path = pg_catalog, analytics
AS $$
DECLARE v_old_state VARCHAR; v_old_since TIMESTAMPTZ; v_changed BOOLEAN;
BEGIN
    SELECT state,state_since INTO v_old_state,v_old_since FROM analytics.machine_current_state
      WHERE machine_id=p_machine FOR UPDATE;
    v_changed := NOT FOUND OR v_old_state IS DISTINCT FROM p_state;
    IF v_changed AND v_old_state IS NOT NULL THEN
      UPDATE analytics.machine_state_interval SET effective_to=p_effective,calculated_at=p_detected
      WHERE machine_id=p_machine AND effective_to IS NULL;
    END IF;
    IF v_changed THEN
      INSERT INTO analytics.machine_state_interval(machine_id,effective_from,state,detected_at)
      VALUES(p_machine,p_effective,p_state,p_detected)
      ON CONFLICT(machine_id,effective_from) DO UPDATE SET state=EXCLUDED.state,detected_at=EXCLUDED.detected_at;
    END IF;
    INSERT INTO analytics.machine_current_state(machine_id,coverage_from,state,state_since,matched_rule_id,
      evaluation_quality,last_telemetry_observed_at,data_through,calculated_at,recalculating)
    VALUES(p_machine,p_coverage_from,p_state,CASE WHEN v_changed THEN p_effective ELSE v_old_since END,
      p_rule,p_quality,p_last_telemetry,p_data_through,p_detected,FALSE)
    ON CONFLICT(machine_id) DO UPDATE SET state=EXCLUDED.state,
      state_since=CASE WHEN analytics.machine_current_state.state IS DISTINCT FROM EXCLUDED.state
        THEN EXCLUDED.state_since ELSE analytics.machine_current_state.state_since END,
      matched_rule_id=EXCLUDED.matched_rule_id,evaluation_quality=EXCLUDED.evaluation_quality,
      last_telemetry_observed_at=EXCLUDED.last_telemetry_observed_at,data_through=EXCLUDED.data_through,
      calculated_at=EXCLUDED.calculated_at,recalculating=FALSE;
    RETURN v_changed;
END $$;
