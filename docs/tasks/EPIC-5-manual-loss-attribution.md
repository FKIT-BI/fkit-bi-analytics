# Эпик 5 — ручная атрибуция потерь

## Реализовано

- Добавлен Liquibase release 012 с каталогом причин, ручными инцидентами, idempotency records, независимыми overlap constraints, tenant-owner trigger и staging-таблицей effective timeline.
- Реализованы LossReason CRUD/list, проверка уникальности и закрытой категории, неизменность кода и категории после использования, мягкое отключение.
- Реализованы ManualIncident create/list/get/update/close/soft-cancel, Enterprise journal, ownership/coverage/range/effect проверки, независимые state/reason overlaps, идемпотентность POST и атомарное добавление minute-aligned dirty work.
- Worker использует существующую durable очередь и stages effective state, machine metrics и loss buckets перед атомарной публикацией. Raw telemetry и calculated state не являются целью manual overlay.
- Назначение/обновление причины сериализуется с изменением статуса блокировкой reason row; POST idempotency lookup/save сериализуется advisory transaction lock по principal/operation/key. Для ongoing reason-only добавлена проверка `from < dataThrough`, в том числе если найден длинный открытый state interval.
- Исторические интервалы, chronometry, loss Pareto/timeseries и текущие reads обогащаются данными LossReason.
- Добавлены PostgreSQL-backed API acceptance tests для normalized name uniqueness, immutability, idempotency conflict/replay, tenant ownership, eligibility, двух overlap dimensions, update/close/cancel и dirty work. В `TelemetryGoldenSliceTest` добавлен end-to-end overlay case: manual SETUP+reason публикует effective interval, metric и loss bucket с правильными incident refs.
- AC-6 retry coverage simulates a lost POST response by repeating the same create request and asserting the original incident ID; different payloads and a different machine using the same key return `409 IDEMPOTENCY_KEY_REUSED`.
- OpenAPI was reconciled with the implemented routes and semantics for normalized duplicate reason names, immutable used category, incident POST idempotency, overlap/no-loss conflicts, and nullable `reasonIncidentId`. YAML parsing succeeded and all 65 pre-existing `operationId` values are preserved.

## Проверка

- `git diff --check` — пройден.
- Полный PostgreSQL-backed Analytics test suite после изменений по review выполнен в Docker с Java 21 командой `./mvnw test`: 67 tests, 0 failures, 0 errors. Узкий прогон целевых тестов до полного также прошёл: 5 tests, 0 failures, 0 errors.
- Проверен edge case reason-only ongoing annotation: старт должен быть строго раньше `dataThrough`, даже если в state timeline есть незакрытый интервал, начинающийся ранее.
- OpenAPI 3.1 YAML parse и проверка сохранения operationId прошли; сохранено 65 значений operationId. Отдельная генерация контрактных клиентов не запускалась.
- Golden-тест проверяет публикацию и late replay сегментов effective timeline с `reasonIncidentId`, а также reason summary/incident reference в current-state, operations overview, historical state-intervals и chronometry. API acceptance test проверяет стабильный cursor paging, отсутствие повторов между страницами и отклонение некорректного cursor.
- API acceptance test проверяет retry после потерянного POST-ответа (тот же ключ/body возвращает исходный incident ID), конфликт того же ключа с иным body и конфликт повторного ключа при другой машине.

## Остаток

- Генерация клиентов/полная внешняя OpenAPI contract-validation команда отдельно не запускались.
- Ручная проверка миграции против отдельного PostgreSQL-инстанса вне Testcontainers не проводилась; migration и integration suite прошли внутри PostgreSQL-backed тестов.
- Генерация клиентов и отдельная внешняя OpenAPI contract-validation команда не запускались; deployment и проверки Web не входят в эту задачу.
- Sprint stories `5.1`–`5.4` переведены в `review`; Epic 5 остаётся `in-progress` до завершения review.
