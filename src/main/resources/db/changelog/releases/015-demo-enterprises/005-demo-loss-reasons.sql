--liquibase formatted sql

--changeset fkit-bi:015-005-demo-loss-reasons context:!test
--comment: Demo loss reason catalogs; incidents are attributed live during the demo
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:2 SELECT count(*) FROM equipment.enterprise WHERE id IN ('682d6ed0-fe3d-0919-038f-b40a0b1871ba','b49eb11c-c6a4-03f9-30e9-c6d6f96f636c')
INSERT INTO analytics.loss_reason (id, enterprise_id, code, name, description, category, color, status) VALUES
    ('9282e385-08b2-aa52-baf6-f85a7aa18ceb', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'CHANGEOVER_SKU', 'Смена SKU / формата', 'Переналадка линии на другой продукт или объём тары', 'CHANGEOVER', '#7C3AED', 'ACTIVE'),
    ('dd3b1d8b-7a3b-f561-d505-5c862d838748', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'CIP_WASH', 'Мойка CIP', 'Санитарная мойка оборудования между партиями', 'CHANGEOVER', '#0EA5E9', 'ACTIVE'),
    ('f95a4ec5-a3b0-7e3e-3e76-9526567a1ccf', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'NO_PACKAGING', 'Нет тары (банки, флаконы, тубы)', 'Тара не подана на линию', 'MATERIAL', '#F59E0B', 'ACTIVE'),
    ('58b0b46a-c160-6bee-d3da-c01f15d10ae7', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'NO_BULK', 'Нет полупродукта (балка)', 'Варочный участок не выдал основу', 'MATERIAL', '#D97706', 'ACTIVE'),
    ('80a9de07-39f9-9e7e-e107-ec552835ad1f', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'NO_LABELS', 'Нет этикетки', 'Закончился или не подан рулон этикетки', 'MATERIAL', '#FBBF24', 'ACTIVE'),
    ('9d0ca891-db89-36e6-f3c3-a394299d19cd', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'PACKAGE_JAM', 'Застревание тары на конвейере', 'Перекос или падение тары, заклинивание конвейера', 'EQUIPMENT', '#EF4444', 'ACTIVE'),
    ('4ba5c508-6a58-1cd1-0ff4-222299ae1233', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'DOSER_FAILURE', 'Неисправность дозатора', 'Отказ поршневой группы или клапанов дозатора', 'EQUIPMENT', '#DC2626', 'ACTIVE'),
    ('ae737ec2-4bfe-f975-46b1-bbc35c0d636c', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'QC_WAIT', 'Ожидание ОТК / микробиологии', 'Партия ждёт решения лаборатории', 'QUALITY', '#10B981', 'ACTIVE'),
    ('11c2c2b2-5cb1-2dd9-baeb-a04f6bde3b28', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'LABELING_DEFECT', 'Брак этикетирования', 'Смещение или складки этикетки', 'QUALITY', '#059669', 'ACTIVE'),
    ('02bad877-2655-366a-7f6e-49173bf29ebb', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'NO_OPERATOR', 'Нет оператора', 'Оператор отсутствует на рабочем месте', 'PERSONNEL', '#8B5CF6', 'ACTIVE'),
    ('5db42517-09e5-7083-bb72-5e50886d1509', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'PLANNED_MAINTENANCE', 'Плановое ТО', 'Техническое обслуживание по графику', 'OTHER', '#64748B', 'ACTIVE'),
    ('f953aac8-3bf4-467f-877a-929701130bb0', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'POWER_OUTAGE', 'Отключение электроэнергии', 'Пропадание питания на вводе', 'EXTERNAL', '#1F2937', 'ACTIVE'),
    ('3ee7c0e5-f5c4-be08-f2e8-1d103a0afd64', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'NO_PALLETS', 'Нет поддонов', 'Поддоны не поданы на участок', 'MATERIAL', '#F59E0B', 'ACTIVE'),
    ('300f93dc-f17e-8e65-d0aa-13b9de56ba89', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'NO_FILM', 'Нет стретч-плёнки', 'Закончилась плёнка для обмотки', 'MATERIAL', '#FBBF24', 'ACTIVE'),
    ('ca4fd347-22b0-e4b5-a595-cf6b21396eee', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'WRAPPER_FAILURE', 'Неисправность обмотчика', 'Отказ поворотного стола или каретки', 'EQUIPMENT', '#EF4444', 'ACTIVE'),
    ('3b4a93cf-d104-09e0-9ba7-29de00b7a4bd', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'NO_TRANSPORT', 'Нет транспорта под погрузку', 'Машина перевозчика не подана', 'EXTERNAL', '#1F2937', 'ACTIVE'),
    ('b8fced89-7567-fafa-a9f0-807cd25147fe', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'NO_STOREKEEPER', 'Нет кладовщика', 'Кладовщик отсутствует на участке', 'PERSONNEL', '#8B5CF6', 'ACTIVE'),
    ('bb620f7c-c2aa-8956-ee5b-476e679cc057', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'PLANNED_MAINTENANCE', 'Плановое ТО', 'Техническое обслуживание по графику', 'OTHER', '#64748B', 'ACTIVE');

