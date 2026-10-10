--liquibase formatted sql

--changeset fkit-bi:002-002-bootstrap-trigger-function splitStatements:false
CREATE FUNCTION identity.reject_bootstrap_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.is_bootstrap IS DISTINCT FROM OLD.is_bootstrap THEN
        RAISE EXCEPTION 'bootstrap marker is immutable';
    END IF;
    RETURN NEW;
END;
$$;
