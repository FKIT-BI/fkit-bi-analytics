--liquibase formatted sql

--changeset fkit-bi:012-001-loss-reason-and-incidents
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE analytics.loss_reason (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL REFERENCES equipment.enterprise(id),
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    normalized_name VARCHAR(200) GENERATED ALWAYS AS (lower(btrim(name))) STORED,
    description VARCHAR(500),
    category VARCHAR(32) NOT NULL CHECK (category IN ('EQUIPMENT','MATERIAL','PERSONNEL','PROCESS','QUALITY','CHANGEOVER','EXTERNAL','OTHER')),
    color VARCHAR(7) CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (enterprise_id, code),
    UNIQUE (enterprise_id, normalized_name),
    UNIQUE (enterprise_id, id)
);

CREATE TABLE analytics.manual_incident (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    starts_at TIMESTAMPTZ(3) NOT NULL,
    ends_at TIMESTAMPTZ(3),
    state_override VARCHAR(16) CHECK (state_override IS NULL OR state_override IN ('SETUP','PLANNED_STOP')),
    loss_reason_id UUID REFERENCES analytics.loss_reason(id),
    comment VARCHAR(500),
    author VARCHAR(100),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    cancelled_at TIMESTAMPTZ(3),
    CHECK (ends_at IS NULL OR ends_at > starts_at),
    CHECK (state_override IS NOT NULL OR loss_reason_id IS NOT NULL)
);
--changeset fkit-bi:012-002-owner-guard-function splitStatements:false
CREATE OR REPLACE FUNCTION analytics.guard_manual_incident_reason_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.loss_reason_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM equipment.machine m JOIN analytics.loss_reason r ON r.enterprise_id=m.enterprise_id
    WHERE m.id=NEW.machine_id AND r.id=NEW.loss_reason_id
  ) THEN
    RAISE EXCEPTION 'INCIDENT_REASON_ENTERPRISE_MISMATCH' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
--changeset fkit-bi:012-003-owner-guard-trigger
CREATE TRIGGER trg_manual_incident_reason_owner BEFORE INSERT OR UPDATE OF machine_id,loss_reason_id ON analytics.manual_incident
FOR EACH ROW EXECUTE FUNCTION analytics.guard_manual_incident_reason_owner();
CREATE INDEX ix_manual_incident_machine_range ON analytics.manual_incident(machine_id,starts_at,ends_at);
CREATE INDEX ix_manual_incident_reason ON analytics.manual_incident(loss_reason_id) WHERE loss_reason_id IS NOT NULL;
ALTER TABLE analytics.manual_incident ADD CONSTRAINT ex_manual_incident_state_overlap
    EXCLUDE USING gist (machine_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&)
    WHERE (cancelled_at IS NULL AND state_override IS NOT NULL) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE analytics.manual_incident ADD CONSTRAINT ex_manual_incident_reason_overlap
    EXCLUDE USING gist (machine_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&)
    WHERE (cancelled_at IS NULL AND loss_reason_id IS NOT NULL) DEFERRABLE INITIALLY IMMEDIATE;

CREATE TABLE analytics.manual_incident_idempotency (
    principal_id VARCHAR(255) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    incident_id UUID NOT NULL REFERENCES analytics.manual_incident(id),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (principal_id,operation,idempotency_key)
);

CREATE TABLE analytics.staging_machine_effective_state_interval (
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
    work_generation BIGINT NOT NULL,
    PRIMARY KEY(machine_id,effective_from,work_generation),
    CHECK(effective_to IS NULL OR effective_to>effective_from)
);
CREATE INDEX ix_staging_effective_work ON analytics.staging_machine_effective_state_interval(machine_id,work_generation,effective_from);
ALTER TABLE analytics.machine_effective_state_interval
  ADD CONSTRAINT fk_effective_state_incident FOREIGN KEY(state_incident_id) REFERENCES analytics.manual_incident(id),
  ADD CONSTRAINT fk_effective_reason_incident FOREIGN KEY(reason_incident_id) REFERENCES analytics.manual_incident(id),
  ADD CONSTRAINT fk_effective_loss_reason FOREIGN KEY(loss_reason_id) REFERENCES analytics.loss_reason(id);
ALTER TABLE analytics.staging_machine_effective_state_interval
  ADD CONSTRAINT fk_staging_effective_state_incident FOREIGN KEY(state_incident_id) REFERENCES analytics.manual_incident(id),
  ADD CONSTRAINT fk_staging_effective_reason_incident FOREIGN KEY(reason_incident_id) REFERENCES analytics.manual_incident(id),
  ADD CONSTRAINT fk_staging_effective_loss_reason FOREIGN KEY(loss_reason_id) REFERENCES analytics.loss_reason(id);

--changeset fkit-bi:012-004-reason-update-function splitStatements:false
CREATE OR REPLACE FUNCTION analytics.guard_loss_reason_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.enterprise_id,NEW.code) IS DISTINCT FROM (OLD.enterprise_id,OLD.code) THEN
    RAISE EXCEPTION 'LOSS_REASON_IDENTITY_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  IF NEW.category IS DISTINCT FROM OLD.category AND EXISTS (SELECT 1 FROM analytics.manual_incident i WHERE i.loss_reason_id=OLD.id) THEN
    RAISE EXCEPTION 'LOSS_REASON_CATEGORY_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  NEW.updated_at=clock_timestamp();
  RETURN NEW;
END $$;
--changeset fkit-bi:012-005-reason-update-trigger
CREATE TRIGGER trg_loss_reason_immutable BEFORE UPDATE ON analytics.loss_reason
FOR EACH ROW EXECUTE FUNCTION analytics.guard_loss_reason_update();

--changeset fkit-bi:012-006-no-delete-function splitStatements:false
CREATE OR REPLACE FUNCTION analytics.reject_manual_attribution_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'MANUAL_ATTRIBUTION_DELETE_FORBIDDEN' USING ERRCODE='23514';
END $$;
--changeset fkit-bi:012-007-no-delete-triggers
CREATE TRIGGER trg_loss_reason_no_delete BEFORE DELETE ON analytics.loss_reason
FOR EACH ROW EXECUTE FUNCTION analytics.reject_manual_attribution_delete();
CREATE TRIGGER trg_manual_incident_no_delete BEFORE DELETE ON analytics.manual_incident
FOR EACH ROW EXECUTE FUNCTION analytics.reject_manual_attribution_delete();

--changeset fkit-bi:012-008-queue-range-function splitStatements:false
CREATE OR REPLACE FUNCTION analytics.queue_incident_range(p_machine UUID,p_from TIMESTAMPTZ,p_to TIMESTAMPTZ)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_from TIMESTAMPTZ; v_to TIMESTAMPTZ;
BEGIN
  v_from=date_trunc('minute',p_from);
  SELECT date_trunc('minute',COALESCE(p_to,c.data_through,clock_timestamp())) +
         CASE WHEN COALESCE(p_to,c.data_through,clock_timestamp())=date_trunc('minute',COALESCE(p_to,c.data_through,clock_timestamp())) THEN interval '0 minute' ELSE interval '1 minute' END
    INTO v_to FROM analytics.machine_current_state c WHERE c.machine_id=p_machine;
  IF v_to IS NULL OR v_to<=v_from THEN v_to=v_from+interval '1 minute'; END IF;
  INSERT INTO telemetry.recalculation_work(machine_id,dirty_from,dirty_to,status)
  VALUES(p_machine,v_from,v_to,'PENDING')
  ON CONFLICT(machine_id) DO UPDATE SET
    dirty_from=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_from ELSE LEAST(telemetry.recalculation_work.dirty_from,EXCLUDED.dirty_from) END,
    dirty_to=CASE WHEN telemetry.recalculation_work.status='IDLE' THEN EXCLUDED.dirty_to ELSE GREATEST(telemetry.recalculation_work.dirty_to,EXCLUDED.dirty_to) END,
    generation=telemetry.recalculation_work.generation+1,
    status=CASE WHEN telemetry.recalculation_work.lease_owner IS NULL THEN 'PENDING' ELSE telemetry.recalculation_work.status END,
    updated_at=clock_timestamp();
  UPDATE analytics.machine_current_state SET recalculating=true WHERE machine_id=p_machine;
END $$;
