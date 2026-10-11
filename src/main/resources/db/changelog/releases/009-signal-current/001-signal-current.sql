--liquibase formatted sql

--changeset fkit-bi:009-001-signal-current
CREATE TABLE analytics.signal_current_value (
    signal_id UUID PRIMARY KEY REFERENCES equipment.telemetry_signal(id),
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    value_type VARCHAR(16) NOT NULL CHECK (value_type IN ('DOUBLE','LONG','BOOLEAN','STRING')),
    last_received_observed_at TIMESTAMPTZ(3),
    last_received_at TIMESTAMPTZ(3),
    received_double DOUBLE PRECISION,
    received_long BIGINT,
    received_boolean BOOLEAN,
    received_text VARCHAR(500),
    received_quality SMALLINT CHECK (received_quality IN (0,1,2)),
    received_flags INTEGER CHECK (received_flags BETWEEN 0 AND 3),
    usable_observed_at TIMESTAMPTZ(3),
    usable_double DOUBLE PRECISION,
    usable_long BIGINT,
    usable_boolean BOOLEAN,
    usable_text VARCHAR(500),
    freshness VARCHAR(16) NOT NULL CHECK (freshness IN ('NO_DATA','FRESH','STALE')),
    data_through TIMESTAMPTZ(3),
    calculated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_signal_current_received_value CHECK (
      (last_received_observed_at IS NULL AND last_received_at IS NULL
        AND num_nonnulls(received_double,received_long,received_boolean,received_text)=0
        AND received_quality IS NULL AND received_flags IS NULL)
      OR (last_received_observed_at IS NOT NULL AND last_received_at IS NOT NULL
        AND num_nonnulls(received_double,received_long,received_boolean,received_text)=1
        AND received_quality IS NOT NULL AND received_flags IS NOT NULL)
    ),
    CONSTRAINT ck_signal_current_usable_value CHECK (
      (usable_observed_at IS NULL AND num_nonnulls(usable_double,usable_long,usable_boolean,usable_text)=0)
      OR (usable_observed_at IS NOT NULL AND num_nonnulls(usable_double,usable_long,usable_boolean,usable_text)=1)
    ),
    CONSTRAINT ck_signal_current_no_data CHECK (freshness <> 'NO_DATA' OR usable_observed_at IS NULL)
);
CREATE INDEX ix_signal_current_machine ON analytics.signal_current_value(machine_id, signal_id);

--changeset fkit-bi:009-002-refresh-signal-current splitStatements:false
CREATE FUNCTION analytics.refresh_signal_current_for_machine(p_machine UUID, p_data_through TIMESTAMPTZ)
RETURNS INTEGER LANGUAGE plpgsql
SET search_path = pg_catalog, equipment, telemetry, analytics
AS $$
DECLARE v_count INTEGER;
BEGIN
    INSERT INTO analytics.signal_current_value(
      signal_id,machine_id,value_type,last_received_observed_at,last_received_at,
      received_double,received_long,received_boolean,received_text,received_quality,received_flags,
      usable_observed_at,usable_double,usable_long,usable_boolean,usable_text,
      freshness,data_through,calculated_at
    )
    SELECT s.id,s.machine_id,s.value_type,r.observed_at,r.received_at,
      r.value_double,r.value_long,r.value_boolean,r.value_text,r.source_quality,r.validation_flags,
      u.observed_at,u.value_double,u.value_long,u.value_boolean,u.value_text,
      CASE WHEN u.observed_at IS NULL THEN 'NO_DATA'
           WHEN u.observed_at < clock_timestamp() - (COALESCE(s.stale_after_ms, s.expected_interval_ms * 3, 60000) * interval '1 millisecond') THEN 'STALE'
           ELSE 'FRESH' END,
      p_data_through,clock_timestamp()
    FROM equipment.telemetry_signal s
    LEFT JOIN LATERAL (
      SELECT raw.observed_at,b.received_at,raw.value_double,raw.value_long,raw.value_boolean,
             raw.value_text,raw.source_quality,raw.validation_flags
      FROM telemetry.raw_measurement raw
      JOIN telemetry.ingest_batch b ON b.id=raw.ingest_batch_id
      WHERE raw.signal_id=s.id AND raw.observed_at >= clock_timestamp() - interval '90 days'
      ORDER BY raw.observed_at DESC,raw.ingest_batch_id DESC,raw.ordinal DESC
      LIMIT 1
    ) r ON TRUE
    LEFT JOIN LATERAL (
      SELECT raw.observed_at,raw.value_double,raw.value_long,raw.value_boolean,raw.value_text
      FROM telemetry.raw_measurement raw
      WHERE raw.signal_id=s.id AND raw.observed_at >= clock_timestamp() - interval '90 days'
        AND raw.source_quality=0 AND raw.validation_flags=0
      ORDER BY raw.observed_at DESC,raw.ingest_batch_id DESC,raw.ordinal DESC
      LIMIT 1
    ) u ON TRUE
    WHERE s.machine_id=p_machine
    ON CONFLICT(signal_id) DO UPDATE SET
      machine_id=EXCLUDED.machine_id,value_type=EXCLUDED.value_type,
      last_received_observed_at=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.last_received_observed_at ELSE EXCLUDED.last_received_observed_at END,
      last_received_at=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.last_received_at ELSE EXCLUDED.last_received_at END,
      received_double=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_double ELSE EXCLUDED.received_double END,
      received_long=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_long ELSE EXCLUDED.received_long END,
      received_boolean=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_boolean ELSE EXCLUDED.received_boolean END,
      received_text=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_text ELSE EXCLUDED.received_text END,
      received_quality=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_quality ELSE EXCLUDED.received_quality END,
      received_flags=CASE WHEN EXCLUDED.last_received_observed_at IS NULL OR analytics.signal_current_value.last_received_observed_at>=EXCLUDED.last_received_observed_at
        THEN analytics.signal_current_value.received_flags ELSE EXCLUDED.received_flags END,
      usable_observed_at=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN analytics.signal_current_value.usable_observed_at ELSE EXCLUDED.usable_observed_at END,
      usable_double=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN analytics.signal_current_value.usable_double ELSE EXCLUDED.usable_double END,
      usable_long=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN analytics.signal_current_value.usable_long ELSE EXCLUDED.usable_long END,
      usable_boolean=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN analytics.signal_current_value.usable_boolean ELSE EXCLUDED.usable_boolean END,
      usable_text=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN analytics.signal_current_value.usable_text ELSE EXCLUDED.usable_text END,
      freshness=CASE WHEN EXCLUDED.usable_observed_at IS NULL OR analytics.signal_current_value.usable_observed_at>=EXCLUDED.usable_observed_at
        THEN CASE WHEN analytics.signal_current_value.usable_observed_at IS NULL THEN EXCLUDED.freshness
                  WHEN analytics.signal_current_value.usable_observed_at < clock_timestamp() -
                    ((SELECT COALESCE(stale_after_ms,expected_interval_ms*3,60000) FROM equipment.telemetry_signal WHERE id=EXCLUDED.signal_id) * interval '1 millisecond')
                    THEN 'STALE' ELSE analytics.signal_current_value.freshness END
        ELSE EXCLUDED.freshness END,
      data_through=EXCLUDED.data_through,calculated_at=EXCLUDED.calculated_at;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END $$;
