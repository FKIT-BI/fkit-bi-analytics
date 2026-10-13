--liquibase formatted sql

--changeset fkit-bi:014-001-expand-telemetry-signal-unit-code
ALTER TABLE equipment.telemetry_signal
    ALTER COLUMN unit_code TYPE VARCHAR(64);
