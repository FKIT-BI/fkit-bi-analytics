--liquibase formatted sql

--changeset fkit-bi:004-001-lines-machines
CREATE TABLE equipment.production_line (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL REFERENCES equipment.enterprise(id),
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_production_line_enterprise_code UNIQUE (enterprise_id, code),
    CONSTRAINT uq_production_line_enterprise_id UNIQUE (enterprise_id, id)
);
CREATE INDEX ix_production_line_enterprise_order ON equipment.production_line(enterprise_id, status, sort_order, code);

CREATE TABLE equipment.machine (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL REFERENCES equipment.enterprise(id),
    line_id UUID,
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    machine_type VARCHAR(100),
    inventory_no VARCHAR(100),
    manufacturer VARCHAR(200),
    model VARCHAR(200),
    commissioning_year SMALLINT CHECK (commissioning_year BETWEEN 1900 AND 9999),
    nominal_rate NUMERIC(20,6) CHECK (nominal_rate > 0),
    production_unit_code VARCHAR(64) REFERENCES equipment.measurement_unit(code),
    sort_order INTEGER CHECK (sort_order >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' CHECK (status IN ('ACTIVE', 'DISABLED')),
    coverage_from TIMESTAMPTZ(3),
    parameters JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(parameters) = 'array'),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_machine_enterprise_code UNIQUE (enterprise_id, code),
    CONSTRAINT uq_machine_enterprise_id UNIQUE (enterprise_id, id),
    CONSTRAINT fk_machine_line_owner FOREIGN KEY (enterprise_id, line_id)
        REFERENCES equipment.production_line(enterprise_id, id),
    CONSTRAINT uq_machine_enterprise_inventory UNIQUE (enterprise_id, inventory_no),
    CONSTRAINT ck_machine_rate_unit CHECK ((nominal_rate IS NULL) = (production_unit_code IS NULL)),
    CONSTRAINT ck_machine_line_sort_order CHECK ((line_id IS NULL) = (sort_order IS NULL))
);
CREATE INDEX ix_machine_enterprise_status_order ON equipment.machine(enterprise_id, status, sort_order, code);
CREATE INDEX ix_machine_line_order ON equipment.machine(line_id, status, sort_order, code) WHERE line_id IS NOT NULL;
