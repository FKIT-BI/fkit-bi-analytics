--liquibase formatted sql

--changeset fkit-bi:003-001-create-enterprise-shifts
CREATE TABLE equipment.enterprise (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    timezone VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE equipment.shift (
    id UUID PRIMARY KEY,
    enterprise_id UUID NOT NULL REFERENCES equipment.enterprise(id),
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    start_local_time TIME(0) NOT NULL,
    end_local_time TIME(0) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shift_enterprise_code UNIQUE (enterprise_id, code),
    CONSTRAINT ck_shift_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT uq_shift_enterprise_id UNIQUE (enterprise_id, id)
);

CREATE INDEX ix_shift_enterprise_status_sort ON equipment.shift (enterprise_id, status, sort_order, code);
CREATE INDEX ix_shift_enterprise_validity ON equipment.shift (enterprise_id, valid_from, valid_to);
