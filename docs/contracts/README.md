# Contracts

## Normative OpenAPI contracts

- `analytics-api-draft.yaml` — Analytics API v1.0.0: конфигурация, мониторинг, аналитика, инциденты и перерасчёт.
- `fkit-bi-generator/docs/contracts/generator-api-draft.yaml` — Generator API v1.0.0: сценарии и запуски генерации.
- `fkit-bi-infra/docs/contracts/auth-api.yaml` — общий Auth API v1.0.0: вход и создание пользователей.
- `draft-api.yaml` — исходный frontend-пример, не нормативный доменный контракт.
- `draft-api-traceability.md` — сопоставление старых и новых операций.
- `userFlow-draft.md` — исходные пользовательские сценарии frontend.

Все три нормативных контракта используют `/api/v1`. Внешние service prefixes задаёт Nginx. Analytics выпускает JWT, Generator и Analytics валидируют Bearer tokens.
Исторические имена файлов с `-draft` оставлены ради существующих ссылок; опубликованная версия контракта в каждом файле — нормативная `1.0.0`.

## Shared database contract

Analytics owns all Liquibase migrations. Generator may append raw telemetry only according to [`raw-telemetry-v1.md`](raw-telemetry-v1.md), which is installed by release 007. The current Flyway bootstrap contains only a schema marker and was replaced by the Liquibase baseline described in `spec-sql-equipment-foundation`.

Historical Analytics reads are projection-backed and bounded: requests cover at most 90 days; metric/loss series return at most 1,000 points and telemetry series at most 720 points. `AUTO` follows the documented 5m/15m/1h/1d thresholds and explicitly requested resolutions exceeding the limit are rejected. Responses expose the requested/actual interval, `dataThrough`, `calculatedAt`, partial/stale/recalculating status, and applied resolution. Shift-scoped metric/loss reads use the minute projection's `shiftId` attribution; a single local shift occurrence is identified by `shiftId + shiftDate`, with timezone boundaries resolved from the owning Enterprise. Chronometry is bounded to 10,000 intervals for the complete response.

State recalculation is also bounded: `PERIOD` reconstructs from the preceding stable state interval plus retained raw context; `ALL` reconstructs from `coverageFrom`. Both require complete retained raw partitions and at most 500,000 raw events, otherwise the request is rejected rather than publishing a partial reconstruction.
