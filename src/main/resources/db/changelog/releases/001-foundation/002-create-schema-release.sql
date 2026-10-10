--liquibase formatted sql

--changeset fkit-bi:001-002-create-schema-release
CREATE TABLE equipment.schema_release (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    release VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO equipment.schema_release (singleton, release)
VALUES (TRUE, '001-foundation');
