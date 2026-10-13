--liquibase formatted sql

--changeset fkit-bi:015-001-demo-enterprises-shifts context:!test
--comment: Demo enterprises (cosmetics plant and finished goods warehouse) with 12-hour day/night shifts
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM equipment.enterprise WHERE code IN ('DEMO_COSMETICS','DEMO_WAREHOUSE')
-- Fixed demo identifiers are md5('fkit-demo:<key>')::uuid, e.g. md5('fkit-demo:machine:JAR_FILLER')::uuid,
-- so state rules and generator scenarios can reference signals deterministically.
INSERT INTO equipment.enterprise (id, code, name, timezone, status) VALUES
    ('682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'DEMO_COSMETICS', 'Демо-фабрика косметики', 'Europe/Moscow', 'ACTIVE'),
    ('b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'DEMO_WAREHOUSE', 'Демо-склад готовой продукции', 'Europe/Moscow', 'ACTIVE');

INSERT INTO equipment.shift (id, enterprise_id, code, name, start_local_time, end_local_time, valid_from, valid_to, sort_order, status) VALUES
    ('a99bde38-bd1c-a984-a023-0f1c0ef74559', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'DAY', 'Дневная смена', '08:00', '20:00', (now() AT TIME ZONE 'Europe/Moscow')::date - 30, NULL, 1, 'ACTIVE'),
    ('a5114519-f3ea-67c3-597f-bcd2d8cb5763', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'NIGHT', 'Ночная смена', '20:00', '08:00', (now() AT TIME ZONE 'Europe/Moscow')::date - 30, NULL, 2, 'ACTIVE'),
    ('0c491b28-62c2-40bc-3c71-41995a8993fa', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'DAY', 'Дневная смена', '08:00', '20:00', (now() AT TIME ZONE 'Europe/Moscow')::date - 30, NULL, 1, 'ACTIVE'),
    ('a5f5e1bd-1272-63db-a063-09ba6f510107', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'NIGHT', 'Ночная смена', '20:00', '08:00', (now() AT TIME ZONE 'Europe/Moscow')::date - 30, NULL, 2, 'ACTIVE');

