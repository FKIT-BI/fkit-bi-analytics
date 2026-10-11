--liquibase formatted sql

--changeset fkit-bi:013-001-generator-scenarios-and-runs
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE generator.generation_scenario (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL REFERENCES equipment.enterprise(id),
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' CHECK (status IN ('ACTIVE','DISABLED')),
    cycle_mode VARCHAR(8) NOT NULL CHECK (cycle_mode IN ('ONCE','LOOP')),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_generation_scenario_enterprise_code UNIQUE (enterprise_id, code),
    CONSTRAINT uq_generation_scenario_enterprise_id UNIQUE (enterprise_id, id)
);
CREATE INDEX ix_generation_scenario_enterprise_status_updated
    ON generator.generation_scenario(enterprise_id, status, updated_at DESC, id);

-- The normalized target links make same-enterprise ownership enforceable by PostgreSQL;
-- the scenario definition JSON remains the replaceable transport/domain document.
CREATE TABLE generator.generation_scenario_machine (
    enterprise_id UUID NOT NULL,
    scenario_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    PRIMARY KEY (scenario_id, machine_id),
    CONSTRAINT fk_generation_scenario_machine_scenario
        FOREIGN KEY (enterprise_id, scenario_id)
        REFERENCES generator.generation_scenario(enterprise_id, id),
    CONSTRAINT fk_generation_scenario_machine_equipment
        FOREIGN KEY (enterprise_id, machine_id)
        REFERENCES equipment.machine(enterprise_id, id)
);
CREATE INDEX ix_generation_scenario_machine_machine
    ON generator.generation_scenario_machine(machine_id, scenario_id);

CREATE TABLE generator.generation_run (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL,
    enterprise_id UUID NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('REALTIME','HISTORICAL','SCHEDULED')),
    status VARCHAR(20) NOT NULL CHECK (status IN (
        'SCHEDULED','QUEUED','RUNNING','STOP_REQUESTED','COMPLETED','STOPPED','CANCELLED','FAILED'
    )),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    random_seed BIGINT NOT NULL,
    event_from TIMESTAMPTZ(3),
    event_to TIMESTAMPTZ(3),
    scheduled_start_at TIMESTAMPTZ(3),
    requested_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ(3),
    finished_at TIMESTAMPTZ(3),
    stop_requested_at TIMESTAMPTZ(3),
    current_phase_code VARCHAR(64),
    cycle_number BIGINT NOT NULL DEFAULT 0 CHECK (cycle_number >= 0),
    generated_through TIMESTAMPTZ(3),
    generated_measurements BIGINT NOT NULL DEFAULT 0 CHECK (generated_measurements >= 0),
    written_measurements BIGINT NOT NULL DEFAULT 0 CHECK (written_measurements >= 0),
    dropped_measurements BIGINT NOT NULL DEFAULT 0 CHECK (dropped_measurements >= 0),
    failed_batches BIGINT NOT NULL DEFAULT 0 CHECK (failed_batches >= 0),
    last_successful_write_at TIMESTAMPTZ(3),
    attempt_no INTEGER NOT NULL DEFAULT 1 CHECK (attempt_no >= 1),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ(3),
    failure_code VARCHAR(64),
    failure_message VARCHAR(500),
    failure_at TIMESTAMPTZ(3),
    failure_retryable BOOLEAN,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_generation_run_enterprise_id UNIQUE (enterprise_id, id),
    CONSTRAINT fk_generation_run_scenario_owner
        FOREIGN KEY (enterprise_id, scenario_id)
        REFERENCES generator.generation_scenario(enterprise_id, id),
    CONSTRAINT ck_generation_run_event_range CHECK (event_to IS NULL OR event_from IS NULL OR event_to > event_from),
    CONSTRAINT ck_generation_run_scheduled_mode CHECK ((mode = 'SCHEDULED') = (scheduled_start_at IS NOT NULL)),
    CONSTRAINT ck_generation_run_historical_range CHECK (
        (mode = 'HISTORICAL' AND event_from IS NOT NULL AND event_to IS NOT NULL) OR mode <> 'HISTORICAL'
    ),
    CONSTRAINT ck_generation_run_lease_pair CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_generation_run_failure_pair CHECK (
        (failure_code IS NULL AND failure_at IS NULL AND failure_retryable IS NULL) OR
        (failure_code IS NOT NULL AND failure_at IS NOT NULL AND failure_retryable IS NOT NULL)
    ),
    CONSTRAINT ck_generation_run_terminal_time CHECK (
        (status IN ('COMPLETED','STOPPED','CANCELLED','FAILED')) = (finished_at IS NOT NULL)
    )
);
CREATE INDEX ix_generation_run_enterprise_requested
    ON generator.generation_run(enterprise_id, requested_at DESC, id DESC);
CREATE INDEX ix_generation_run_active
    ON generator.generation_run(status, requested_at, id)
    WHERE status IN ('SCHEDULED','QUEUED','RUNNING','STOP_REQUESTED');
CREATE INDEX ix_generation_run_scenario_requested
    ON generator.generation_run(scenario_id, requested_at DESC, id DESC);

-- Immutable request identity survives later changes to the Scenario and its equipment.
CREATE TABLE generator.generation_run_idempotency (
    principal_id VARCHAR(255) NOT NULL CHECK (length(btrim(principal_id)) > 0),
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(btrim(idempotency_key)) > 0),
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    run_id UUID NOT NULL REFERENCES generator.generation_run(id),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (principal_id, idempotency_key),
    CONSTRAINT uq_generation_run_idempotency_run UNIQUE (run_id)
);

CREATE TABLE generator.generation_run_attempt (
    run_id UUID NOT NULL REFERENCES generator.generation_run(id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    started_at TIMESTAMPTZ(3),
    finished_at TIMESTAMPTZ(3),
    failure_code VARCHAR(64),
    failure_message VARCHAR(500),
    failure_at TIMESTAMPTZ(3),
    failure_retryable BOOLEAN,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, attempt_no),
    CONSTRAINT ck_generation_run_attempt_times CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_generation_run_attempt_failure_pair CHECK (
        (failure_code IS NULL AND failure_at IS NULL AND failure_retryable IS NULL) OR
        (failure_code IS NOT NULL AND failure_at IS NOT NULL AND failure_retryable IS NOT NULL)
    )
);

-- One row per target machine; application claims these under short transaction try-locks.
CREATE TABLE generator.generation_run_reservation (
    enterprise_id UUID NOT NULL,
    run_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    reservation_kind VARCHAR(16) NOT NULL CHECK (reservation_kind IN ('REALTIME','SCHEDULED','HISTORICAL')),
    reserved_from TIMESTAMPTZ(3),
    reserved_to TIMESTAMPTZ(3),
    released_at TIMESTAMPTZ(3),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, machine_id),
    CONSTRAINT fk_generation_run_reservation_run
        FOREIGN KEY (enterprise_id, run_id)
        REFERENCES generator.generation_run(enterprise_id, id),
    CONSTRAINT fk_generation_run_reservation_machine
        FOREIGN KEY (enterprise_id, machine_id)
        REFERENCES equipment.machine(enterprise_id, id),
    CONSTRAINT ck_generation_run_reservation_range CHECK (
        reserved_from IS NULL OR reserved_to IS NULL OR reserved_to > reserved_from
    ),
    CONSTRAINT ck_generation_run_reservation_range_required CHECK (
        reserved_from IS NOT NULL AND
        (reservation_kind <> 'HISTORICAL' OR reserved_to IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_generation_run_active_realtime_machine
    ON generator.generation_run_reservation(machine_id)
    WHERE reservation_kind = 'REALTIME' AND released_at IS NULL;
ALTER TABLE generator.generation_run_reservation
    -- A NULL reserved_to is an open-ended reservation, including unbounded scheduled LOOP.
    -- It excludes every later reservation on that machine until released at terminal state.
    ADD CONSTRAINT ex_generation_run_machine_reservation_overlap
    EXCLUDE USING gist (
        machine_id WITH =,
        tstzrange(reserved_from, reserved_to, '[)') WITH &&
    ) WHERE (released_at IS NULL)
    DEFERRABLE INITIALLY IMMEDIATE;
CREATE INDEX ix_generation_run_reservation_run ON generator.generation_run_reservation(run_id, released_at);

-- Historical capacity is a single durable claim slot; delete the row on release.
CREATE TABLE generator.historical_capacity_slot (
    slot_key SMALLINT PRIMARY KEY CHECK (slot_key = 1),
    run_id UUID NOT NULL UNIQUE REFERENCES generator.generation_run(id),
    acquired_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE generator.generation_run_checkpoint (
    run_id UUID PRIMARY KEY REFERENCES generator.generation_run(id),
    completed_through TIMESTAMPTZ(3),
    current_phase_code VARCHAR(64),
    cycle_number BIGINT NOT NULL DEFAULT 0 CHECK (cycle_number >= 0),
    producer_sequence BIGINT NOT NULL DEFAULT 0 CHECK (producer_sequence >= 0),
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(checkpoint) = 'object'),
    generated_measurements BIGINT NOT NULL DEFAULT 0 CHECK (generated_measurements >= 0),
    written_measurements BIGINT NOT NULL DEFAULT 0 CHECK (written_measurements >= 0),
    dropped_measurements BIGINT NOT NULL DEFAULT 0 CHECK (dropped_measurements >= 0),
    failed_batches BIGINT NOT NULL DEFAULT 0 CHECK (failed_batches >= 0),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE generator.historical_coverage (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL,
    run_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    event_from TIMESTAMPTZ(3) NOT NULL,
    event_to TIMESTAMPTZ(3) NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_historical_coverage_range CHECK (event_to > event_from),
    CONSTRAINT fk_historical_coverage_run
        FOREIGN KEY (enterprise_id, run_id)
        REFERENCES generator.generation_run(enterprise_id, id),
    CONSTRAINT fk_historical_coverage_machine
        FOREIGN KEY (enterprise_id, machine_id)
        REFERENCES equipment.machine(enterprise_id, id),
    CONSTRAINT uq_historical_coverage_chunk UNIQUE (run_id, machine_id, event_from, event_to)
);
ALTER TABLE generator.historical_coverage
    ADD CONSTRAINT ex_historical_coverage_machine_overlap
    EXCLUDE USING gist (
        machine_id WITH =,
        tstzrange(event_from, event_to, '[)') WITH &&
    ) DEFERRABLE INITIALLY IMMEDIATE;
CREATE INDEX ix_historical_coverage_run ON generator.historical_coverage(run_id, machine_id, event_from);

-- A Run may advance operational fields, but its captured source of truth cannot drift.
--changeset fkit-bi:013-002-protect-scenario-and-run-snapshots splitStatements:false
CREATE FUNCTION generator.guard_scenario_and_run_identity()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, generator
AS $$
BEGIN
    IF TG_TABLE_NAME = 'generation_scenario' THEN
        IF (NEW.id, NEW.enterprise_id, NEW.code) IS DISTINCT FROM (OLD.id, OLD.enterprise_id, OLD.code) THEN
            RAISE EXCEPTION 'GENERATION_SCENARIO_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
    ELSIF (NEW.id, NEW.enterprise_id, NEW.scenario_id, NEW.snapshot, NEW.random_seed)
          IS DISTINCT FROM (OLD.id, OLD.enterprise_id, OLD.scenario_id, OLD.snapshot, OLD.random_seed) THEN
        RAISE EXCEPTION 'GENERATION_RUN_SNAPSHOT_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

--changeset fkit-bi:013-003-scenario-identity-trigger
CREATE TRIGGER trg_generation_scenario_identity
BEFORE UPDATE ON generator.generation_scenario
FOR EACH ROW EXECUTE FUNCTION generator.guard_scenario_and_run_identity();

--changeset fkit-bi:013-004-run-snapshot-trigger
CREATE TRIGGER trg_generation_run_snapshot
BEFORE UPDATE ON generator.generation_run
FOR EACH ROW EXECUTE FUNCTION generator.guard_scenario_and_run_identity();

--changeset fkit-bi:013-005-reject-coverage-mutation splitStatements:false
CREATE FUNCTION generator.reject_historical_coverage_mutation()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, generator
AS $$
BEGIN
    RAISE EXCEPTION 'HISTORICAL_COVERAGE_IS_APPEND_ONLY' USING ERRCODE = '55000';
END $$;

--changeset fkit-bi:013-006-coverage-append-only-trigger
CREATE TRIGGER trg_historical_coverage_append_only
BEFORE UPDATE OR DELETE ON generator.historical_coverage
FOR EACH ROW EXECUTE FUNCTION generator.reject_historical_coverage_mutation();
