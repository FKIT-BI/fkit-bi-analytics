# Epic 2 — дополнительная автоматизация QA

Дата: 2026-10-11

## Область

Добавлены PostgreSQL 16 Testcontainers-интеграционные проверки реализованных backend-историй
Epic 2 в Analytics. Production-код, Web и незавершённая реализация Epic 6 не изменялись.

## Добавленное покрытие

- Сквозной путь AC-1: Enterprise, две непересекающиеся Shift, Line, напрямую назначенная и
  свободная Machine, одноразовый batch assignment, Component, Signal, StateConfig, readiness и
  активация.
- Повторное назначение Machine отклоняется без изменения ownership.
- Отключение Line не каскадирует сохранённый status Machine, но readiness сообщает
  `LINE_DISABLED`; detail endpoints сохраняют историческую доступность ресурсов.
- Чужой Component и несовместимая пара signal type/kind отклоняются без частичной записи Signal.
- Невалидная замена StateConfig возвращает `422` и сохраняет последний committed document.
- Конфликтующее изменение Shift возвращает `409` и сохраняет исходное расписание и обе Shift.
- Cleanup Story 2.1 больше не удаляет `generator.generation_run`, поэтому тесты Epic 2 совместимы
  с добавляемой после них схемой Epic 6.

## Проверка

Команда выполнена в отдельной временной копии репозитория, чтобы не использовать общий `target/`:

```text
./mvnw -q -Dtest=EquipmentConfigurationApiTest,EnterpriseShiftApiTest,ShiftCalendarTest test
```

Результат: 23 теста, 0 failures, 0 errors, 0 skipped.
