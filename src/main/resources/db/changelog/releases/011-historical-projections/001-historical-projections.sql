--liquibase formatted sql

--changeset fkit-bi:011-001-analytics-projections
CREATE TABLE analytics.machine_effective_state_interval (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    effective_from TIMESTAMPTZ(3) NOT NULL,
    effective_to TIMESTAMPTZ(3),
    effective_state VARCHAR(16) NOT NULL CHECK (effective_state IN ('RUN','IDLE','DOWN','SETUP','PLANNED_STOP','OFFLINE')),
    calculated_state VARCHAR(16) NOT NULL CHECK (calculated_state IN ('RUN','IDLE','DOWN','SETUP','PLANNED_STOP','OFFLINE')),
    state_source VARCHAR(16) NOT NULL CHECK (state_source IN ('CALCULATED','MANUAL')),
    state_incident_id UUID,
    loss_reason_id UUID,
    reason_incident_id UUID,
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (machine_id,effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE UNIQUE INDEX uq_machine_effective_interval_open ON analytics.machine_effective_state_interval(machine_id) WHERE effective_to IS NULL;
CREATE INDEX ix_machine_effective_interval_time ON analytics.machine_effective_state_interval(machine_id,effective_from,effective_to);

CREATE TABLE analytics.signal_numeric_rollup_1m (
    signal_id UUID NOT NULL REFERENCES equipment.telemetry_signal(id),
    bucket_start TIMESTAMPTZ(3) NOT NULL,
    sample_count INTEGER NOT NULL CHECK (sample_count >= 0),
    usable_count INTEGER NOT NULL CHECK (usable_count >= 0),
    good_count INTEGER NOT NULL CHECK (good_count >= 0),
    uncertain_count INTEGER NOT NULL CHECK (uncertain_count >= 0),
    bad_count INTEGER NOT NULL CHECK (bad_count >= 0),
    out_of_range_count INTEGER NOT NULL CHECK (out_of_range_count >= 0),
    min_value DOUBLE PRECISION,
    max_value DOUBLE PRECISION,
    sum_value DOUBLE PRECISION,
    first_value DOUBLE PRECISION,
    last_value DOUBLE PRECISION,
    first_observed_at TIMESTAMPTZ(3),
    last_observed_at TIMESTAMPTZ(3),
    delta_value DOUBLE PRECISION,
    reset_count INTEGER,
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (signal_id,bucket_start),
    CHECK (sample_count = good_count + uncertain_count + bad_count),
    CHECK (usable_count <= good_count AND out_of_range_count <= sample_count),
    CHECK (date_trunc('minute',bucket_start)=bucket_start)
);

CREATE TABLE analytics.machine_metric_bucket_1m (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    bucket_start TIMESTAMPTZ(3) NOT NULL,
    shift_id UUID REFERENCES equipment.shift(id),
    shift_date DATE,
    covered_duration_ms BIGINT NOT NULL CHECK (covered_duration_ms >= 0),
    run_duration_ms BIGINT NOT NULL CHECK (run_duration_ms >= 0),
    idle_duration_ms BIGINT NOT NULL CHECK (idle_duration_ms >= 0),
    down_duration_ms BIGINT NOT NULL CHECK (down_duration_ms >= 0),
    setup_duration_ms BIGINT NOT NULL CHECK (setup_duration_ms >= 0),
    planned_stop_duration_ms BIGINT NOT NULL CHECK (planned_stop_duration_ms >= 0),
    effective_offline_duration_ms BIGINT NOT NULL CHECK (effective_offline_duration_ms >= 0),
    underlying_offline_duration_ms BIGINT NOT NULL CHECK (underlying_offline_duration_ms >= 0),
    produced_quantity DOUBLE PRECISION,
    scrap_quantity DOUBLE PRECISION,
    stop_count INTEGER NOT NULL DEFAULT 0 CHECK (stop_count >= 0),
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count >= 0),
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (machine_id,bucket_start),
    CHECK ((shift_id IS NULL) = (shift_date IS NULL)),
    CHECK (date_trunc('minute',bucket_start)=bucket_start)
);
CREATE INDEX ix_machine_metric_shift ON analytics.machine_metric_bucket_1m(shift_id,shift_date,machine_id,bucket_start);

CREATE TABLE analytics.machine_loss_bucket_1m (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    bucket_start TIMESTAMPTZ(3) NOT NULL,
    shift_id UUID REFERENCES equipment.shift(id),
    shift_date DATE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('IDLE','DOWN','SETUP','PLANNED_STOP')),
    loss_reason_id UUID,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CHECK ((shift_id IS NULL) = (shift_date IS NULL)),
    CHECK (date_trunc('minute',bucket_start)=bucket_start),
    UNIQUE NULLS NOT DISTINCT (machine_id,bucket_start,state,loss_reason_id)
);
CREATE INDEX ix_machine_loss_shift ON analytics.machine_loss_bucket_1m(shift_id,shift_date,machine_id,bucket_start);

CREATE TABLE analytics.state_recalculation_job (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('PERIOD','ALL')),
    requested_from TIMESTAMPTZ(3), requested_to TIMESTAMPTZ(3),
    effective_from TIMESTAMPTZ(3), effective_to TIMESTAMPTZ(3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    progress NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 1),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ(3), completed_at TIMESTAMPTZ(3),
    error_code VARCHAR(64), error_message VARCHAR(500),
    CHECK ((scope='PERIOD' AND requested_from IS NOT NULL AND requested_to IS NOT NULL AND requested_from<requested_to)
        OR (scope='ALL' AND requested_from IS NULL AND requested_to IS NULL))
);
CREATE INDEX ix_state_recalculation_job_machine ON analytics.state_recalculation_job(machine_id,created_at DESC,id DESC);
CREATE UNIQUE INDEX uq_active_state_recalculation_job ON analytics.state_recalculation_job(machine_id)
    WHERE status IN ('PENDING','RUNNING');
ALTER TABLE telemetry.recalculation_work ADD COLUMN recalculation_job_id UUID
    REFERENCES analytics.state_recalculation_job(id);

-- Staging remains independent of active projections. The bounded worker populates these rows,
-- validates them, then swaps a complete minute range in one transaction.
CREATE TABLE analytics.staging_signal_numeric_rollup_1m (LIKE analytics.signal_numeric_rollup_1m INCLUDING DEFAULTS);
ALTER TABLE analytics.staging_signal_numeric_rollup_1m ADD COLUMN work_machine_id UUID NOT NULL, ADD COLUMN work_generation BIGINT NOT NULL;
CREATE INDEX ix_staging_signal_rollup_work ON analytics.staging_signal_numeric_rollup_1m(work_machine_id,work_generation);
CREATE TABLE analytics.staging_machine_state_interval (
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    effective_from TIMESTAMPTZ(3) NOT NULL,
    effective_to TIMESTAMPTZ(3),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUN','IDLE','DOWN','SETUP','PLANNED_STOP','OFFLINE')),
    detected_at TIMESTAMPTZ(3) NOT NULL,
    work_generation BIGINT NOT NULL,
    PRIMARY KEY(machine_id,effective_from,work_generation),
    CHECK (effective_to IS NULL OR effective_to>effective_from)
);
CREATE INDEX ix_staging_machine_state_work ON analytics.staging_machine_state_interval(machine_id,work_generation,effective_from);
CREATE TABLE analytics.staging_machine_metric_bucket_1m (LIKE analytics.machine_metric_bucket_1m INCLUDING DEFAULTS);
ALTER TABLE analytics.staging_machine_metric_bucket_1m ADD COLUMN work_generation BIGINT NOT NULL;
CREATE INDEX ix_staging_machine_metric_work ON analytics.staging_machine_metric_bucket_1m(machine_id,work_generation);
CREATE TABLE analytics.staging_machine_loss_bucket_1m (LIKE analytics.machine_loss_bucket_1m INCLUDING DEFAULTS);
ALTER TABLE analytics.staging_machine_loss_bucket_1m ADD COLUMN work_generation BIGINT NOT NULL;
CREATE INDEX ix_staging_machine_loss_work ON analytics.staging_machine_loss_bucket_1m(machine_id,work_generation);
