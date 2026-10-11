# Epic 2: конфигурация оборудования — Analytics

## Изменения

- Release 004 добавляет `production_line` и `machine`, scoped-коды, ownership FK, ограничения инвентарного номера, rate/unit и `line_id`/`sort_order`, API списков, CRUD, статусов, назначения пачкой и readiness.
- Release 005 добавляет дерево `component`, каталог `telemetry_signal` и бессрочную `telemetry.signal_usage`. Родительская ссылка и component-ссылка сигнала ограничены той же Machine составным FK. Сервис дерева сериализует изменения на owning Machine и отклоняет циклы.
- Release 006 хранит один JSONB `machine_state_config` на Machine. `validate` не пишет, `PUT` валидирует и сохраняет документ целиком. Проверяются AST, лимиты, операторы, типы literals, ownership/status/freshness signal references и правила.
- Машинная активация использует ту же проверку readiness, что и отдельное чтение; отсутствие rate и необязательных production signals выдаёт warnings.
- Созданы ресурсы OpenAPI types для Analytics и Auth. Web использует Bearer-токен в `sessionStorage` и отдельные requests по ресурсу.

## Состояние проверки

Stories 2.2–2.4 реализованы. Полный `./mvnw -q test` пройден в Docker Maven/JDK 21 с PostgreSQL Testcontainers; дополнительно целевой `unreadyMachineCannotBeActivated` прошёл после добавления проверки атомарного отказа активации (422, статус остаётся DISABLED). Миграции 004 → 005 → 006 успешно применились в тестовых БД.

Контрактом first-use lock является `docs/contracts/signal-first-use-lock.md`: Epic 3 writer должен брать shared advisory lock и записывать durable usage marker в той же raw-write транзакции. Story 2.5/UI остаётся у Web-разработчика; Analytics публикует отдельные endpoints в `docs/contracts/analytics-api-draft.yaml`.
