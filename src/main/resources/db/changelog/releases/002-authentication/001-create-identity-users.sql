--liquibase formatted sql

--changeset fkit-bi:002-001-authentication
CREATE SCHEMA identity;

CREATE TABLE identity.users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    is_bootstrap BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_username_normalized CHECK (username = lower(username)),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    CONSTRAINT users_display_name_nonblank CHECK (length(btrim(display_name)) BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX users_username_unique ON identity.users (username);
CREATE UNIQUE INDEX users_single_bootstrap ON identity.users (is_bootstrap) WHERE is_bootstrap;

UPDATE equipment.schema_release
SET release = '002-authentication', updated_at = CURRENT_TIMESTAMP
WHERE singleton = TRUE;
