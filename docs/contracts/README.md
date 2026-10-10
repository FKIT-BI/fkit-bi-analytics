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

Analytics owns all Liquibase migrations. Generator may append raw telemetry only according to the versioned contract added here with each relevant migration. The current Flyway bootstrap contains only a schema marker and will be replaced by the Liquibase baseline described in `spec-sql-equipment-foundation`.
