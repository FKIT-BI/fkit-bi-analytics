--liquibase formatted sql

--changeset fkit-bi:005-001-components-signals
CREATE TABLE equipment.component (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    parent_component_id UUID,
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('LOGICAL_GROUP','ASSEMBLY','MODULE','MECHANISM','MOTOR','DRIVE','GEARBOX','SPINDLE','BEARING','COUPLING','ACTUATOR','PUMP','COMPRESSOR','FAN','BLOWER','VALVE','HEATER','COOLER','MIXER','DOSER','CONVEYOR','FEEDER','ROBOT','MANIPULATOR','TOOL','FIXTURE','SENSOR','INSTRUMENT','CONTROLLER','SAFETY_DEVICE','POWER_SUPPLY','LUBRICATION_SYSTEM','HYDRAULIC_SYSTEM','PNEUMATIC_SYSTEM','COOLING_SYSTEM','OTHER')),
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    parameters JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(parameters)='array'),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_component_machine_code UNIQUE (machine_id,code),
    CONSTRAINT uq_component_machine_id UNIQUE (machine_id,id),
    CONSTRAINT fk_component_parent_owner FOREIGN KEY (machine_id,parent_component_id) REFERENCES equipment.component(machine_id,id),
    CONSTRAINT ck_component_not_own_parent CHECK (parent_component_id IS NULL OR id<>parent_component_id)
);
CREATE INDEX ix_component_machine_tree ON equipment.component(machine_id,parent_component_id,status,sort_order,code);

CREATE TABLE equipment.telemetry_signal (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES equipment.machine(id),
    component_id UUID,
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description VARCHAR(500),
    value_type VARCHAR(16) NOT NULL CHECK (value_type IN ('DOUBLE','LONG','BOOLEAN','STRING')),
    signal_kind VARCHAR(16) NOT NULL CHECK (signal_kind IN ('GAUGE','COUNTER','STATE')),
    semantic_code VARCHAR(64) NOT NULL REFERENCES equipment.signal_semantic(code),
    custom_semantic_code VARCHAR(64),
    unit_code VARCHAR(50) REFERENCES equipment.measurement_unit(code),
    expected_interval_ms BIGINT CHECK (expected_interval_ms>0),
    stale_after_ms BIGINT CHECK (stale_after_ms>0),
    valid_min DOUBLE PRECISION,
    valid_max DOUBLE PRECISION,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER CHECK (display_order>=0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_signal_machine_code UNIQUE (machine_id,code),
    CONSTRAINT uq_signal_machine_id UNIQUE (machine_id,id),
    CONSTRAINT fk_signal_component_owner FOREIGN KEY (machine_id,component_id) REFERENCES equipment.component(machine_id,id),
    CONSTRAINT ck_signal_custom_semantic CHECK ((semantic_code='CUSTOM')=(custom_semantic_code IS NOT NULL)),
    CONSTRAINT ck_signal_custom_code CHECK (custom_semantic_code IS NULL OR custom_semantic_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_signal_stale_interval CHECK (expected_interval_ms IS NULL OR stale_after_ms IS NULL OR stale_after_ms>=expected_interval_ms),
    CONSTRAINT ck_signal_bounds CHECK (valid_min IS NULL OR valid_max IS NULL OR valid_min<=valid_max),
    CONSTRAINT ck_signal_numeric_bounds CHECK ((valid_min IS NULL AND valid_max IS NULL) OR value_type IN ('DOUBLE','LONG')),
    CONSTRAINT ck_signal_type_kind CHECK (
      (signal_kind IN ('GAUGE','COUNTER') AND value_type IN ('DOUBLE','LONG')) OR
      (signal_kind='STATE' AND value_type IN ('BOOLEAN','LONG','STRING'))
    )
);
CREATE INDEX ix_signal_machine_status_order ON equipment.telemetry_signal(machine_id,status,display_order,code);

-- Durable first-use marker survives raw telemetry retention. First-use writers in Epic 3
-- take pg_advisory_xact_lock_shared(hashtextextended(signal_id::text, 0)) before inserting here.
CREATE TABLE telemetry.signal_usage (
    signal_id UUID PRIMARY KEY REFERENCES equipment.telemetry_signal(id),
    first_used_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
