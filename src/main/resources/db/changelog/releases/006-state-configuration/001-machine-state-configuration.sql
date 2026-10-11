--liquibase formatted sql

--changeset fkit-bi:006-001-machine-state-configuration
CREATE TABLE analytics.machine_state_config (
    machine_id UUID PRIMARY KEY REFERENCES equipment.machine(id),
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration)='object'),
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
