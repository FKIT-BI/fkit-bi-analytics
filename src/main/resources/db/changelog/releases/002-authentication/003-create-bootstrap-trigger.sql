--liquibase formatted sql

--changeset fkit-bi:002-003-bootstrap-trigger
CREATE TRIGGER users_bootstrap_marker_immutable
BEFORE UPDATE OF is_bootstrap ON identity.users
FOR EACH ROW EXECUTE FUNCTION identity.reject_bootstrap_change();
