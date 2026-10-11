--liquibase formatted sql

--changeset fkit-bi:008-001-durable-telemetry-work
CREATE TABLE telemetry.recalculation_work (
    machine_id UUID PRIMARY KEY REFERENCES equipment.machine(id),
    dirty_from TIMESTAMPTZ(3) NOT NULL,
    dirty_to TIMESTAMPTZ(3) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    checkpoint_at TIMESTAMPTZ(3),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('IDLE','PENDING','RUNNING','RETRY')),
    lease_owner UUID,
    lease_until TIMESTAMPTZ(3),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    last_error VARCHAR(500),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_recalculation_work_range CHECK (dirty_from < dirty_to),
    CONSTRAINT ck_recalculation_work_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_recalculation_work_claim ON telemetry.recalculation_work(status, lease_until, dirty_from);

--changeset fkit-bi:008-002-materialize-recalculation-hints splitStatements:false
CREATE FUNCTION telemetry.materialize_recalculation_hints(p_limit INTEGER DEFAULT 500)
RETURNS INTEGER LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    IF p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'invalid hint limit' USING ERRCODE = '22023';
    END IF;
    WITH picked AS MATERIALIZED (
      SELECT h.id, h.machine_id, h.from_observed_at, h.to_observed_at
      FROM telemetry.recalculation_hint h
      WHERE h.consumed_at IS NULL
      ORDER BY h.id
      LIMIT p_limit
      FOR UPDATE SKIP LOCKED
    ), grouped AS (
      SELECT machine_id,
        date_trunc('minute', min(from_observed_at)) AS dirty_from,
        date_trunc('minute', max(to_observed_at) - interval '1 millisecond') + interval '1 minute' AS dirty_to
      FROM picked GROUP BY machine_id
    ), merged AS (
      INSERT INTO telemetry.recalculation_work(machine_id, dirty_from, dirty_to)
      SELECT machine_id, dirty_from, dirty_to FROM grouped
      ON CONFLICT (machine_id) DO UPDATE SET
        dirty_from = CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_from
                          ELSE LEAST(telemetry.recalculation_work.dirty_from, EXCLUDED.dirty_from) END,
        dirty_to = CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_to
                        ELSE GREATEST(telemetry.recalculation_work.dirty_to, EXCLUDED.dirty_to) END,
        generation = telemetry.recalculation_work.generation + 1,
        status = CASE WHEN telemetry.recalculation_work.lease_owner IS NULL THEN 'PENDING'
                      ELSE telemetry.recalculation_work.status END,
        updated_at = clock_timestamp()
      RETURNING machine_id
    )
    UPDATE telemetry.recalculation_hint h SET consumed_at = clock_timestamp()
    FROM picked p WHERE h.id = p.id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END $$;

--changeset fkit-bi:008-003-claim-recalculation-work splitStatements:false
CREATE FUNCTION telemetry.claim_recalculation_work(p_owner UUID, p_limit INTEGER DEFAULT 10,
    p_lease INTERVAL DEFAULT interval '30 seconds')
RETURNS SETOF telemetry.recalculation_work LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
BEGIN
    IF p_limit < 1 OR p_limit > 100 OR p_lease <= interval '0 seconds' OR p_lease > interval '5 minutes' THEN
        RAISE EXCEPTION 'invalid work claim parameters' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    WITH candidates AS (
      SELECT machine_id FROM telemetry.recalculation_work
      WHERE status IN ('PENDING','RETRY') OR (status='RUNNING' AND lease_until < clock_timestamp())
      ORDER BY dirty_from, machine_id
      FOR UPDATE SKIP LOCKED LIMIT p_limit
    )
    UPDATE telemetry.recalculation_work w
    SET status='RUNNING', lease_owner=p_owner, lease_until=clock_timestamp()+p_lease,
        attempt=attempt+1, updated_at=clock_timestamp()
    FROM candidates c WHERE w.machine_id=c.machine_id
    RETURNING w.*;
END $$;

--changeset fkit-bi:008-004-finish-recalculation-work splitStatements:false
CREATE FUNCTION telemetry.finish_recalculation_work(p_machine UUID, p_owner UUID,
    p_generation BIGINT, p_checkpoint TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE v_updated INTEGER;
BEGIN
    UPDATE telemetry.recalculation_work
    SET checkpoint_at=p_checkpoint,
        status=CASE WHEN generation=p_generation THEN 'IDLE' ELSE 'PENDING' END,
        lease_owner=NULL, lease_until=NULL, attempt=0, last_error=NULL, updated_at=clock_timestamp()
    WHERE machine_id=p_machine AND lease_owner=p_owner;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    IF v_updated = 0 THEN RETURN FALSE; END IF;
    RETURN TRUE;
END $$;

--changeset fkit-bi:008-005-release-recalculation-work splitStatements:false
CREATE FUNCTION telemetry.release_recalculation_work(p_machine UUID, p_owner UUID, p_error TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql
SET search_path = pg_catalog, telemetry
AS $$
DECLARE v_updated INTEGER;
BEGIN
    UPDATE telemetry.recalculation_work SET status='RETRY', lease_owner=NULL, lease_until=NULL,
      last_error=left(p_error,500), updated_at=clock_timestamp()
    WHERE machine_id=p_machine AND lease_owner=p_owner;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    RETURN v_updated > 0;
END $$;

--changeset fkit-bi:008-006-retention-blocker-includes-work splitStatements:false
CREATE OR REPLACE FUNCTION telemetry.raw_partition_has_retention_blocker(p_day DATE)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, telemetry
AS $$
DECLARE
    v_from TIMESTAMPTZ := p_day::timestamp AT TIME ZONE 'UTC';
    v_to TIMESTAMPTZ := (p_day + 1)::timestamp AT TIME ZONE 'UTC';
BEGIN
    IF EXISTS (
      SELECT 1 FROM telemetry.recalculation_hint h
      WHERE h.from_observed_at < v_to AND h.to_observed_at > v_from AND h.consumed_at IS NULL
    ) THEN RETURN TRUE; END IF;
    IF to_regclass('telemetry.recalculation_work') IS NOT NULL THEN
      RETURN EXISTS (
        SELECT 1 FROM telemetry.recalculation_work w
        WHERE w.dirty_from < v_to AND w.dirty_to > v_from
          AND w.status <> 'IDLE'
      );
    END IF;
    RETURN FALSE;
END $$;
