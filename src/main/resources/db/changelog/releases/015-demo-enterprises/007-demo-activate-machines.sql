--liquibase formatted sql

--changeset fkit-bi:015-007-demo-activate-machines context:!test
--comment: Activate ready demo machines with coverage starting seven days back
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:2 SELECT count(*) FROM equipment.enterprise WHERE id IN ('682d6ed0-fe3d-0919-038f-b40a0b1871ba','b49eb11c-c6a4-03f9-30e9-c6d6f96f636c')
-- Mirrors machine activation (MachineRepository.status) but back-dates coverage to local midnight seven days ago,
-- so historical generator runs for the past week land inside analytics coverage.
UPDATE equipment.machine
SET status = 'ACTIVE', coverage_from = (date_trunc('day', now() AT TIME ZONE 'Europe/Moscow') - interval '7 days') AT TIME ZONE 'Europe/Moscow', updated_at = CURRENT_TIMESTAMP
WHERE id IN ('76969427-b170-81e0-3ad7-9df300f51ded', 'dc281812-91cf-4d6f-1476-a7a262365701', '65e07ed9-92b8-7bfc-e2fa-a699c4e39b83', '2137ba42-a9b8-92c7-4b10-acc048b8a63d', '24dc2d76-a516-430e-53ff-aedb675c05df', 'd95e6d12-5b8c-caab-fe3c-38b159a28ce5', '24f0bbb5-5167-55ef-c220-7c6dec2bf384', '86d30d05-4589-d200-acf0-1b34637d3b0e', 'a740b2a7-d11c-25f9-e2d4-fc5ef6eccef1', 'e1715706-ab0c-0dc2-e025-3f6848b6e58d', 'ff448a04-c131-88b9-dec0-808794ac4587', 'cea9cda8-c375-5569-55d0-bb6f2ac379d5', '03b3b268-54ce-ae2b-5e1c-7820151e40b6', '12ab5909-bb65-0779-3732-095fead554b5');

INSERT INTO telemetry.recalculation_work (machine_id, dirty_from, dirty_to)
SELECT id, coverage_from, coverage_from + interval '1 millisecond' FROM equipment.machine
WHERE id IN ('76969427-b170-81e0-3ad7-9df300f51ded', 'dc281812-91cf-4d6f-1476-a7a262365701', '65e07ed9-92b8-7bfc-e2fa-a699c4e39b83', '2137ba42-a9b8-92c7-4b10-acc048b8a63d', '24dc2d76-a516-430e-53ff-aedb675c05df', 'd95e6d12-5b8c-caab-fe3c-38b159a28ce5', '24f0bbb5-5167-55ef-c220-7c6dec2bf384', '86d30d05-4589-d200-acf0-1b34637d3b0e', 'a740b2a7-d11c-25f9-e2d4-fc5ef6eccef1', 'e1715706-ab0c-0dc2-e025-3f6848b6e58d', 'ff448a04-c131-88b9-dec0-808794ac4587', 'cea9cda8-c375-5569-55d0-bb6f2ac379d5', '03b3b268-54ce-ae2b-5e1c-7820151e40b6', '12ab5909-bb65-0779-3732-095fead554b5')
ON CONFLICT (machine_id) DO NOTHING;

