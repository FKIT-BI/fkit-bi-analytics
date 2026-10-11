--liquibase formatted sql

--changeset fkit-bi:007-001-raw-telemetry
-- Preserve the existing first-use table and its rows while moving it to the
-- event/receipt timestamps required by the telemetry contract.
ALTER TABLE telemetry.signal_usage
    ADD COLUMN first_observed_at TIMESTAMPTZ(3) DEFAULT clock_timestamp(),
    ADD COLUMN first_received_at TIMESTAMPTZ(3) DEFAULT clock_timestamp();
UPDATE telemetry.signal_usage
SET first_observed_at = first_used_at,
    first_received_at = first_used_at;
ALTER TABLE telemetry.signal_usage
    ALTER COLUMN first_observed_at SET NOT NULL,
    ALTER COLUMN first_received_at SET NOT NULL;

CREATE SEQUENCE telemetry.ingest_batch_id_seq AS BIGINT CACHE 100;
CREATE TABLE telemetry.ingest_batch (
    id BIGINT PRIMARY KEY DEFAULT nextval('telemetry.ingest_batch_id_seq'),
    producer_id VARCHAR(128) NOT NULL CHECK (length(btrim(producer_id)) > 0),
    producer_batch_id VARCHAR(128) NOT NULL CHECK (length(btrim(producer_batch_id)) > 0),
    producer_sequence BIGINT NOT NULL CHECK (producer_sequence >= 0),
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    generator_run_id UUID,
    mode SMALLINT NOT NULL CHECK (mode IN (1, 2)),
    received_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    measurement_count INTEGER NOT NULL CHECK (measurement_count BETWEEN 1 AND 1000),
    min_observed_at TIMESTAMPTZ(3) NOT NULL,
    max_observed_at TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT uq_ingest_batch_producer_key UNIQUE (producer_id, producer_batch_id),
    CONSTRAINT ck_ingest_batch_range CHECK (min_observed_at <= max_observed_at)
);
CREATE INDEX ix_ingest_batch_retention ON telemetry.ingest_batch(max_observed_at, id);

CREATE TABLE telemetry.raw_partition_registry (
    partition_day DATE PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'RETIRING')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    retiring_at TIMESTAMPTZ(3)
);

CREATE TABLE telemetry.raw_measurement (
    ingest_batch_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 999),
    signal_id UUID NOT NULL,
    observed_at TIMESTAMPTZ(3) NOT NULL,
    value_double DOUBLE PRECISION,
    value_long BIGINT,
    value_boolean BOOLEAN,
    value_text VARCHAR(500),
    source_quality SMALLINT NOT NULL DEFAULT 0 CHECK (source_quality IN (0, 1, 2)),
    validation_flags INTEGER NOT NULL DEFAULT 0 CHECK (validation_flags BETWEEN 0 AND 3),
    CONSTRAINT ck_raw_measurement_exactly_one_value CHECK (
      num_nonnulls(value_double, value_long, value_boolean, value_text) = 1
    )
) PARTITION BY RANGE (observed_at);
CREATE INDEX ix_raw_measurement_signal_time ON telemetry.raw_measurement(signal_id, observed_at);

--changeset fkit-bi:007-002-reject-raw-mutation splitStatements:false
CREATE FUNCTION telemetry.reject_raw_measurement_mutation()
RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
BEGIN
    RAISE EXCEPTION 'raw_measurement is append-only' USING ERRCODE = '55000';
END $$;
CREATE TRIGGER trg_raw_measurement_append_only
BEFORE UPDATE OR DELETE ON telemetry.raw_measurement
FOR EACH ROW EXECUTE FUNCTION telemetry.reject_raw_measurement_mutation();

CREATE TABLE telemetry.recalculation_hint (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingest_batch_id BIGINT NOT NULL REFERENCES telemetry.ingest_batch(id),
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    from_observed_at TIMESTAMPTZ(3) NOT NULL,
    to_observed_at TIMESTAMPTZ(3) NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    consumed_at TIMESTAMPTZ(3),
    CONSTRAINT ck_recalculation_hint_range CHECK (from_observed_at < to_observed_at),
    CONSTRAINT uq_recalculation_hint_batch_machine UNIQUE (ingest_batch_id, machine_id)
);
CREATE INDEX ix_recalculation_hint_pending ON telemetry.recalculation_hint(created_at, id)
    WHERE consumed_at IS NULL;

-- Fixed namespace keys coordinate partition topology and per-day ingestion/retention.
--changeset fkit-bi:007-003-raw-partition-lock-key splitStatements:false
CREATE FUNCTION telemetry.raw_partition_day_lock_key(p_day DATE)
RETURNS BIGINT LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog, telemetry
AS $$ SELECT hashtextextended('fkit-bi:raw-day:' || p_day::text, 0) $$;

--changeset fkit-bi:007-004-create-raw-partition splitStatements:false
CREATE FUNCTION telemetry.create_raw_partition(p_day DATE)
RETURNS BOOLEAN LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_today DATE := (clock_timestamp() AT TIME ZONE 'UTC')::date;
    v_name TEXT := 'raw_measurement_' || to_char(p_day, 'YYYYMMDD');
BEGIN
    IF p_day < v_today - 89 OR p_day > v_today + 7 THEN
        RAISE EXCEPTION 'partition day outside active window: %', p_day USING ERRCODE = '22023';
    END IF;
    IF NOT pg_try_advisory_xact_lock(hashtextextended('fkit-bi:raw-topology', 0)) THEN
        RETURN FALSE;
    END IF;
    IF NOT pg_try_advisory_xact_lock(telemetry.raw_partition_day_lock_key(p_day)) THEN
        RETURN FALSE;
    END IF;
    IF EXISTS (SELECT 1 FROM telemetry.raw_partition_registry WHERE partition_day = p_day AND status = 'RETIRING') THEN
        RETURN FALSE;
    END IF;
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS telemetry.%I PARTITION OF telemetry.raw_measurement FOR VALUES FROM (%L) TO (%L)',
      v_name, p_day::timestamp AT TIME ZONE 'UTC', (p_day + 1)::timestamp AT TIME ZONE 'UTC'
    );
    INSERT INTO telemetry.raw_partition_registry(partition_day, status)
    VALUES (p_day, 'ACTIVE') ON CONFLICT (partition_day) DO UPDATE
      SET status = 'ACTIVE', retiring_at = NULL;
    RETURN TRUE;
END $$;

--changeset fkit-bi:007-005-ensure-raw-partition-window splitStatements:false
CREATE FUNCTION telemetry.ensure_raw_partition_window()
RETURNS INTEGER LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_today DATE := (clock_timestamp() AT TIME ZONE 'UTC')::date;
    v_day DATE;
    v_created INTEGER := 0;
BEGIN
    FOR v_day IN SELECT v_today - 89 + n FROM generate_series(0, 96) AS n LOOP
        IF telemetry.create_raw_partition(v_day) THEN
            v_created := v_created + 1;
        END IF;
    END LOOP;
    RETURN v_created;
END $$;

--changeset fkit-bi:007-006-retention-blocker splitStatements:false
CREATE FUNCTION telemetry.raw_partition_has_retention_blocker(p_day DATE)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_from TIMESTAMPTZ := p_day::timestamp AT TIME ZONE 'UTC';
    v_to TIMESTAMPTZ := (p_day + 1)::timestamp AT TIME ZONE 'UTC';
BEGIN
    -- Durable seam: later work/lease/publication tables can extend this guard
    -- without making this migration depend on objects that do not exist yet.
    RETURN EXISTS (
      SELECT 1 FROM telemetry.recalculation_hint h
      WHERE h.from_observed_at < v_to AND h.to_observed_at > v_from
        AND h.consumed_at IS NULL
    );
END $$;

--changeset fkit-bi:007-007-retire-raw-partition splitStatements:false
CREATE FUNCTION telemetry.retire_raw_partition(p_day DATE)
RETURNS BOOLEAN LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_today DATE := (clock_timestamp() AT TIME ZONE 'UTC')::date;
    v_name TEXT := 'raw_measurement_' || to_char(p_day, 'YYYYMMDD');
BEGIN
    IF p_day >= v_today - 89 THEN RETURN FALSE; END IF;
    IF NOT pg_try_advisory_xact_lock(hashtextextended('fkit-bi:raw-topology', 0)) THEN RETURN FALSE; END IF;
    IF NOT pg_try_advisory_xact_lock(telemetry.raw_partition_day_lock_key(p_day)) THEN RETURN FALSE; END IF;
    UPDATE telemetry.raw_partition_registry SET status = 'RETIRING', retiring_at = clock_timestamp()
    WHERE partition_day = p_day AND status = 'ACTIVE';
    IF NOT FOUND THEN RETURN FALSE; END IF;
    IF telemetry.raw_partition_has_retention_blocker(p_day) THEN
        UPDATE telemetry.raw_partition_registry SET status = 'ACTIVE', retiring_at = NULL WHERE partition_day = p_day;
        RETURN FALSE;
    END IF;
    BEGIN
        PERFORM set_config('lock_timeout', '1500ms', true);
        PERFORM set_config('statement_timeout', '5s', true);
        EXECUTE format('DROP TABLE telemetry.%I', v_name);
    EXCEPTION WHEN undefined_table THEN
        NULL;
    WHEN lock_not_available OR query_canceled THEN
        UPDATE telemetry.raw_partition_registry SET status = 'ACTIVE', retiring_at = NULL WHERE partition_day = p_day;
        RETURN FALSE;
    END;
    DELETE FROM telemetry.raw_partition_registry WHERE partition_day = p_day;
    RETURN TRUE;
END $$;

--changeset fkit-bi:007-008-initialize-raw-partition-window
SELECT telemetry.ensure_raw_partition_window();
