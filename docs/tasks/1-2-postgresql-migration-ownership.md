# Story 1.2 — Analytics

- Bootstrap Flyway заменён на Liquibase 4.31.1 с явными неизменяемыми SQL includes релиза `001-foundation`.
- Foundation создаёт пять схем, `equipment.schema_release` и согласованные справочники semantic и measurement unit.
- Readiness проверяет PostgreSQL и точный release `001-foundation`; диагностические ответы не содержат текста SQL-ошибок. При ошибке миграции сервис остаётся запущен, readiness сообщает `MIGRATION_FAILED`.
- PostgreSQL Testcontainers покрывают ошибку миграции, чистую/legacy БД, повторный запуск, сохранность Flyway/marker-данных, seed-коды и ограничения целостности.
- Проверка: Java 21 `./mvnw -B verify` прошёл (17 тестов). Вывод сборки перенаправлялся во временный `/tmp`, так как существующая папка `target/` принадлежит root.
