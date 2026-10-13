# STABLE-API-001 — стабильный Analytics API и понятные ошибки

Спека: `fkit-bi-infra/docs/bmad/implementation-artifacts/spec-stable-analytics-api-and-readable-errors.md`.
Источник проблем: HAR с `bi.kitexp.ru` от 2026-10-13.

## Реализовано

- `GET /enterprises/{id}/loss-reasons` без `category`/`search` больше не падает с 500: nullable-параметры SQL явно типизированы (`?::text`), иначе PostgreSQL не может вывести тип `? IS NULL`.
- Incidents (`/machines/{id}/incidents`, `/enterprises/{id}/incidents`): `limit` по умолчанию 100, максимум 500. Ошибки `limit`/`cursor`/`effectType`/диапазона возвращают `400 VALIDATION_FAILED` с точным `fieldErrors[].path`.
- Live-периоды: summary, metrics/timeseries, telemetry/series, losses timeseries/pareto и state-intervals принимают `to` в будущем. Чтение ограничено `min(requestedTo, now)`, `metadata.requestedTo` сохраняется, `partial=true`. `PREVIOUS_PERIOD` сравнивает только прошедшую часть текущего периода, в том числе для shift occurrence.
- Неверный диапазон возвращает `400 INVALID_RANGE` с `fieldErrors[].path=from|to`. Ошибки параметров analytics-контроллера приведены к 400, как в OpenAPI.
- `GET /reference/measurement-units`: активные `code/name/symbol/quantityCode`.
- Машина: неполная пара `nominalRate`/`productionUnit` → `422`, path поля, `REQUIRED_WITH`; неизвестная единица → `UNKNOWN_UNIT`. Сигнал: `unitCode` → `UNKNOWN_UNIT`. Единица параметра машины → `parameters[i].unitCode`.
- Liquibase 014: `equipment.telemetry_signal.unit_code` расширен до `VARCHAR(64)`, как PK справочника единиц.
- `ApiExceptionHandler`: русские безопасные сообщения; Bean Validation даёт стабильные коды полей (`REQUIRED`, `INVALID_LENGTH`, `INVALID_FORMAT`, `OUT_OF_RANGE`); type mismatch и отсутствующий query-параметр → 400 с path; 500 логирует stack trace вместе с `traceId`, в ответ попадает только безопасный текст.
- OpenAPI: справочник единиц, `dependentRequired` для rate/unit, maxLength 64, семантика live-периодов, примеры ошибок 400/422, описание 500. Добавлен contract test, который фиксирует HAR-поведение.

## Ревью и исправления после первого прохода

- Ready boundary по `min(data_through)` убрана: одна отстающая машина обрезала бы «Сегодня» для всего предприятия, а для пустой области появился бы 404. Граница — wall clock; свежесть данных по-прежнему в `metadata.dataThrough`.
- В contract test не было `listMeasurementUnits` — сборка бы упала.

## Проверка

- Docker `maven:3.9.11-eclipse-temurin-21`, `mvn -B verify` с Testcontainers PostgreSQL: 87 tests, 0 failures, BUILD SUCCESS.
- Новые/изменённые тесты: loss-reasons без фильтров, limit default/500/501, единицы и field errors машины/сигнала, live summary/pareto/timeseries с будущим `to`, `INVALID_RANGE` path `from`.
- `fkit-bi-infra/scripts/test-api-contracts.py`: 68 операций Analytics, lint пройден.

## Остаток

- Причина 500 у loss-reasons подтверждена кодом и тестом на PostgreSQL 16, но не логами DEV по traceId из HAR. После деплоя нужно повторить сценарии HAR.
