# Epic 1 — дополнительная автоматизация QA

Дата: 2026-10-11

## Область

Добавлены интеграционные проверки реализованных историй 1.1–1.3 в Analytics. Production-код,
Generator и незавершённые миграции более поздних Epic не изменялись.

## Добавленное покрытие

- Отдельный test-only Liquibase changelog фиксирует историческую границу Epic 1:
  `001-foundation` + `002-authentication`, независимо от текущего master changelog.
- PostgreSQL 16 Testcontainers проверяет clean database, повторное применение миграций,
  сохранение legacy marker/Flyway metadata, шесть схем, release `002-authentication`, отсутствие
  таблиц последующих Epic и ограничения `identity.users`.
- Auth API проверяет malformed JSON с корреляцией `X-Trace-Id`, безопасное тело ошибки,
  72-byte UTF-8 ограничение BCrypt и общий error envelope для anonymous business request.
- Fixed-window тест доверенного proxy устойчив к переходу календарной минуты во время прогона.

## Проверка

Команда выполнена в отдельной временной копии репозитория, чтобы не использовать общий `target/`:

```text
./mvnw -B -Dtest=AnalyticsApplicationTest,LiquibaseFailureReadinessTest,DatabaseUnavailableReadinessTest,FoundationMigrationTest,EpicOneMigrationContractTest,AuthFlowTest,TraceIdFilterTest test
```

Результат: `BUILD SUCCESS`; 35 тестов, 0 failures, 0 errors, 0 skipped.

## Обнаруженный gap

`auth-api.yaml` задаёт `additionalProperties: false`, но неизвестное поле JSON в login сейчас
игнорируется десериализатором и запрос доходит до проверки credentials. Исправление требует
изменения production-конфигурации Jackson и сознательно не выполнялось в test-only задаче.
