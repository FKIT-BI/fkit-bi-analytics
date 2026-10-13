--liquibase formatted sql

--changeset fkit-bi:015-002-demo-lines-machines context:!test
--comment: Demo production lines, machines with passports, one unassigned spare and one commissioning draft
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:2 SELECT count(*) FROM equipment.enterprise WHERE id IN ('682d6ed0-fe3d-0919-038f-b40a0b1871ba','b49eb11c-c6a4-03f9-30e9-c6d6f96f636c')
INSERT INTO equipment.production_line (id, enterprise_id, code, name, sort_order, status) VALUES
    ('38311221-75a4-aac3-0344-903acbea12d1', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'PREPARATION', 'Варочный участок', 1, 'ACTIVE'),
    ('4a3a9252-5db4-d71e-7d4e-0aabdd803cc9', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'JAR_FILLING', 'Линия розлива кремов', 2, 'ACTIVE'),
    ('24ea9008-5d1b-51e9-3fa5-4d4b989682e9', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'BOTTLE_FILLING', 'Линия розлива шампуней', 3, 'ACTIVE'),
    ('2b7d89fa-1e09-27dc-9a53-3d89ad606891', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', 'TUBES_PACKING', 'Линия туб и упаковки', 4, 'ACTIVE'),
    ('fce59411-86fb-cbf1-977b-cdf991abf40c', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'SHIPPING', 'Участок отгрузки', 1, 'ACTIVE');

-- Machines start DISABLED; 007 activates the ready ones with a back-dated coverage start.
INSERT INTO equipment.machine (id, enterprise_id, line_id, code, name, machine_type, inventory_no, manufacturer, model,
    commissioning_year, nominal_rate, production_unit_code, sort_order, status, parameters) VALUES
    ('76969427-b170-81e0-3ad7-9df300f51ded', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '38311221-75a4-aac3-0344-903acbea12d1', 'HOMOGENIZER_500', 'Вакуумный гомогенизатор ВГ-500',
     'Вакуумный гомогенизатор', 'COS-0101', 'Демомаш', 'ВГ-500', 2019, 250, 'LITER', 1, 'DISABLED',
     '[{"code":"VESSEL_VOLUME","name":"Рабочий объём котла","valueType":"NUMBER","value":500,"sortOrder":1,"unitCode":"LITER"},{"code":"MAX_TEMPERATURE","name":"Максимальная температура","valueType":"NUMBER","value":95,"sortOrder":2,"unitCode":"CELSIUS"},{"code":"HOMOGENIZER_INSTALLED","name":"Встроенный гомогенизатор","valueType":"BOOLEAN","value":true,"sortOrder":3},{"code":"PRODUCT","name":"Продукт","valueType":"STRING","value":"Крем-основа для лица","sortOrder":4}]'::jsonb),
    ('dc281812-91cf-4d6f-1476-a7a262365701', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '38311221-75a4-aac3-0344-903acbea12d1', 'MIXER_1000', 'Реактор-смеситель РС-1000',
     'Реактор-смеситель', 'COS-0102', 'Демомаш', 'РС-1000', 2017, 450, 'LITER', 2, 'DISABLED',
     '[{"code":"VESSEL_VOLUME","name":"Рабочий объём котла","valueType":"NUMBER","value":1000,"sortOrder":1,"unitCode":"LITER"},{"code":"MAX_TEMPERATURE","name":"Максимальная температура","valueType":"NUMBER","value":90,"sortOrder":2,"unitCode":"CELSIUS"},{"code":"HOMOGENIZER_INSTALLED","name":"Встроенный гомогенизатор","valueType":"BOOLEAN","value":false,"sortOrder":3},{"code":"PRODUCT","name":"Продукт","valueType":"STRING","value":"Шампунь-основа","sortOrder":4}]'::jsonb),
    ('65e07ed9-92b8-7bfc-e2fa-a699c4e39b83', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '38311221-75a4-aac3-0344-903acbea12d1', 'WATER_RO', 'Установка водоподготовки ОС-2000',
     'Установка обратного осмоса', 'COS-0103', 'АкваДемо', 'ОС-2000', 2020, 1500, 'LITER', 3, 'DISABLED',
     '[{"code":"CAPACITY","name":"Производительность по пермеату","valueType":"NUMBER","value":1500,"sortOrder":1,"unitCode":"LITER"},{"code":"MEMBRANES","name":"Количество мембран","valueType":"NUMBER","value":4,"sortOrder":2},{"code":"WATER_GRADE","name":"Качество воды","valueType":"STRING","value":"Вода очищенная","sortOrder":3}]'::jsonb),
    ('2137ba42-a9b8-92c7-4b10-acc048b8a63d', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '4a3a9252-5db4-d71e-7d4e-0aabdd803cc9', 'JAR_FILLER', 'Дозатор-наполнитель ДН-4П',
     'Поршневой дозатор', 'COS-0201', 'ДозаТех', 'ДН-4П', 2021, 2400, 'PCS', 1, 'DISABLED',
     '[{"code":"NOZZLES","name":"Количество дозирующих головок","valueType":"NUMBER","value":4,"sortOrder":1},{"code":"DOSE_RANGE","name":"Диапазон дозы","valueType":"STRING","value":"10–100 мл","sortOrder":2},{"code":"CURRENT_SKU","name":"Текущий SKU","valueType":"STRING","value":"Крем для лица увлажняющий, 50 мл","sortOrder":3},{"code":"CIP_SUPPORTED","name":"Поддержка мойки CIP","valueType":"BOOLEAN","value":true,"sortOrder":4}]'::jsonb),
    ('24dc2d76-a516-430e-53ff-aedb675c05df', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '4a3a9252-5db4-d71e-7d4e-0aabdd803cc9', 'JAR_CAPPER', 'Укупорочный автомат УА-2',
     'Укупорщик', 'COS-0202', 'УкупорМаш', 'УА-2', 2021, 2600, 'PCS', 2, 'DISABLED',
     '[{"code":"CAP_TYPE","name":"Тип крышки","valueType":"STRING","value":"Винтовая крышка 58 мм","sortOrder":1},{"code":"TORQUE","name":"Момент затяжки, Н·м","valueType":"NUMBER","value":1.8,"sortOrder":2}]'::jsonb),
    ('d95e6d12-5b8c-caab-fe3c-38b159a28ce5', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '4a3a9252-5db4-d71e-7d4e-0aabdd803cc9', 'JAR_LABELER', 'Этикетировщик ЭТ-300',
     'Этикетировочная машина', 'COS-0203', 'ЭтикетСервис', 'ЭТ-300', 2018, 2700, 'PCS', 3, 'DISABLED',
     '[{"code":"LABEL_TYPE","name":"Тип этикетки","valueType":"STRING","value":"Самоклеящаяся, круговая","sortOrder":1},{"code":"ROLL_LENGTH","name":"Длина рулона","valueType":"NUMBER","value":500,"sortOrder":2,"unitCode":"METER"}]'::jsonb),
    ('24f0bbb5-5167-55ef-c220-7c6dec2bf384', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '4a3a9252-5db4-d71e-7d4e-0aabdd803cc9', 'JAR_CHECKWEIGHER', 'Контрольные весы КВ-1',
     'Чеквейер', 'COS-0204', 'ВесКонтроль', 'КВ-1', 2022, 2800, 'PCS', 4, 'DISABLED',
     '[{"code":"TARGET_WEIGHT","name":"Номинальная масса","valueType":"NUMBER","value":0.052,"sortOrder":1,"unitCode":"KILOGRAM"},{"code":"TOLERANCE","name":"Допуск","valueType":"STRING","value":"±1,5 г","sortOrder":2}]'::jsonb),
    ('86d30d05-4589-d200-acf0-1b34637d3b0e', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '24ea9008-5d1b-51e9-3fa5-4d4b989682e9', 'BOTTLE_MONOBLOCK', 'Моноблок розлива и укупорки МРУ-6',
     'Моноблок розлива', 'COS-0301', 'ДозаТех', 'МРУ-6', 2016, 3600, 'PCS', 1, 'DISABLED',
     '[{"code":"NOZZLES","name":"Количество дозирующих головок","valueType":"NUMBER","value":6,"sortOrder":1},{"code":"DOSE_RANGE","name":"Диапазон дозы","valueType":"STRING","value":"100–500 мл","sortOrder":2},{"code":"CURRENT_SKU","name":"Текущий SKU","valueType":"STRING","value":"Шампунь для объёма, 250 мл","sortOrder":3},{"code":"CIP_SUPPORTED","name":"Поддержка мойки CIP","valueType":"BOOLEAN","value":true,"sortOrder":4}]'::jsonb),
    ('a740b2a7-d11c-25f9-e2d4-fc5ef6eccef1', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '24ea9008-5d1b-51e9-3fa5-4d4b989682e9', 'BOTTLE_LABELER', 'Этикетировщик ЭТ-500',
     'Этикетировочная машина', 'COS-0302', 'ЭтикетСервис', 'ЭТ-500', 2016, 3800, 'PCS', 2, 'DISABLED',
     '[{"code":"LABEL_TYPE","name":"Тип этикетки","valueType":"STRING","value":"Самоклеящаяся, лицевая и контрэтикетка","sortOrder":1},{"code":"ROLL_LENGTH","name":"Длина рулона","valueType":"NUMBER","value":1000,"sortOrder":2,"unitCode":"METER"}]'::jsonb),
    ('e1715706-ab0c-0dc2-e025-3f6848b6e58d', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '24ea9008-5d1b-51e9-3fa5-4d4b989682e9', 'BOTTLE_SHRINK', 'Термоупаковщик ТУ-60',
     'Термоусадочная машина', 'COS-0303', 'ТермоПак', 'ТУ-60', 2019, 650, 'PCS', 3, 'DISABLED',
     '[{"code":"PACK_FORMAT","name":"Формат упаковки","valueType":"STRING","value":"Групповая упаковка 6 флаконов","sortOrder":1},{"code":"TUNNEL_TEMP","name":"Температура туннеля","valueType":"NUMBER","value":180,"sortOrder":2,"unitCode":"CELSIUS"}]'::jsonb),
    ('ff448a04-c131-88b9-dec0-808794ac4587', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '2b7d89fa-1e09-27dc-9a53-3d89ad606891', 'TUBE_FILLER', 'Тубонаполнитель ТН-50',
     'Тубонаполнитель', 'COS-0401', 'ДозаТех', 'ТН-50', 2022, 3000, 'PCS', 1, 'DISABLED',
     '[{"code":"NOZZLES","name":"Количество дозирующих головок","valueType":"NUMBER","value":2,"sortOrder":1},{"code":"DOSE_RANGE","name":"Диапазон дозы","valueType":"STRING","value":"3–200 мл","sortOrder":2},{"code":"CURRENT_SKU","name":"Текущий SKU","valueType":"STRING","value":"Крем для рук, туба 75 мл","sortOrder":3},{"code":"CIP_SUPPORTED","name":"Поддержка мойки CIP","valueType":"BOOLEAN","value":false,"sortOrder":4}]'::jsonb),
    ('cea9cda8-c375-5569-55d0-bb6f2ac379d5', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '2b7d89fa-1e09-27dc-9a53-3d89ad606891', 'CARTONER', 'Картонажная машина КМ-120',
     'Картонажная машина', 'COS-0402', 'КартонЛайн', 'КМ-120', 2022, 3200, 'PCS', 2, 'DISABLED',
     '[{"code":"CARTON_FORMAT","name":"Формат коробки","valueType":"STRING","value":"Индивидуальная пачка","sortOrder":1}]'::jsonb),
    ('39bff56f-e53c-58dc-f5a8-526227d79c09', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', '2b7d89fa-1e09-27dc-9a53-3d89ad606891', 'TUBE_FILLER_NEW', 'Тубонаполнитель ТН-80 (пусконаладка)',
     'Тубонаполнитель', 'COS-0403', 'ДозаТех', 'ТН-80', 2026, 3600, 'PCS', 3, 'DISABLED',
     '[{"code":"NOZZLES","name":"Количество дозирующих головок","valueType":"NUMBER","value":2,"sortOrder":1},{"code":"COMMISSIONING","name":"Статус","valueType":"STRING","value":"Пусконаладочные работы","sortOrder":2}]'::jsonb),
    ('82c350f6-53a6-8ff2-eb71-29b761b2b9a5', '682d6ed0-fe3d-0919-038f-b40a0b1871ba', NULL, 'SPARE_DOSER', 'Резервный дозатор ДН-2П',
     'Поршневой дозатор', 'COS-0901', 'ДозаТех', 'ДН-2П', 2014, 1800, 'PCS', NULL, 'DISABLED',
     '[{"code":"NOZZLES","name":"Количество дозирующих головок","valueType":"NUMBER","value":2,"sortOrder":1},{"code":"LOCATION","name":"Место хранения","valueType":"STRING","value":"Склад оборудования, ячейка Б-4","sortOrder":2}]'::jsonb),
    ('03b3b268-54ce-ae2b-5e1c-7820151e40b6', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'fce59411-86fb-cbf1-977b-cdf991abf40c', 'SORT_CONVEYOR', 'Сортировочный конвейер СК-12',
     'Сортировочный конвейер', 'WH-0101', 'ЛогистикТех', 'СК-12', 2020, 900, 'PCS', 1, 'DISABLED',
     '[{"code":"CHUTES","name":"Количество направлений","valueType":"NUMBER","value":12,"sortOrder":1},{"code":"UNIT","name":"Единица обработки","valueType":"STRING","value":"Короб","sortOrder":2}]'::jsonb),
    ('12ab5909-bb65-0779-3732-095fead554b5', 'b49eb11c-c6a4-03f9-30e9-c6d6f96f636c', 'fce59411-86fb-cbf1-977b-cdf991abf40c', 'PALLET_WRAPPER', 'Паллетообмотчик ПО-2',
     'Паллетообмотчик', 'WH-0102', 'ЛогистикТех', 'ПО-2', 2019, 30, 'PCS', 2, 'DISABLED',
     '[{"code":"FILM","name":"Плёнка","valueType":"STRING","value":"Стретч-плёнка 23 мкм","sortOrder":1},{"code":"MAX_PALLET_HEIGHT","name":"Максимальная высота паллеты","valueType":"NUMBER","value":2.2,"sortOrder":2,"unitCode":"METER"}]'::jsonb);

