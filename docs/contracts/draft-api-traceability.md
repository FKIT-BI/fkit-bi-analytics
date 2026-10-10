# Трассировка `draft-api.yaml` → `analytics-api-draft.yaml`

Старый контракт сохраняется как источник frontend-сценариев, но не является ограничением доменной модели. Новый API использует base path `/api/v1`, UUID, стабильные `code`, отдельные configuration resources и подготовленные analytical projections.

## Существующие операции

| Старый operationId | Решение | Новый operationId / изменение |
| --- | --- | --- |
| `listEnterprises` | CHANGE | `listEnterprises`: status/search/page filters, стабильные code/status/timestamps |
| `createEnterprise` | CHANGE | `createEnterprise`: Shift больше не вложен в Enterprise input |
| `getEnterprise` | CHANGE | `getEnterprise`: собственный status и стабильный code |
| `updateEnterprise` | CHANGE | `updateEnterprise`: code/status не меняются, timezone блокируется после temporal data |
| `listLines` | CHANGE | `listLines`: status/search/page, `sortOrder` вместо `order` |
| `createLine` | CHANGE | `createLine`: обязательный code, status backend default ACTIVE |
| `getLine` | CHANGE | `getLine`: owner, code, status и timestamps |
| `updateLine` | CHANGE | `updateLine`: только name/sortOrder; status отдельной командой |
| `listMachines` | CHANGE | `listLineMachines` плюс `listEnterpriseMachines` для assigned/unassigned flow |
| `createMachine` | REPLACE | `createMachine` вложен в Enterprise, принимает optional lineId и всегда создаёт DISABLED |
| `getMachine` | SPLIT | `getMachine` возвращает core/passport; components/signals/state config читаются отдельно |
| `updateMachine` | SPLIT | Core update отделён от line assignment, status, components, signals и rules |
| `listLossReasons` | CHANGE | Enterprise-scoped `listLossReasons` с status/category/search/page |
| `createLossReason` | CHANGE | Enterprise-scoped code/category; `isPlanned` удалён, плановость следует state |
| `updateLossReason` | CHANGE | `getLossReason`, `updateLossReason`, `updateLossReasonStatus`; category блокируется после использования |
| `listIncidents` | CHANGE | Enterprise-scoped общий journal и machine-scoped journal с effect/reason/ongoing/cancelled filters |
| `createIncident` | CHANGE | Path задаёт machine; incident может иметь state override, reason или оба эффекта |
| `getIncident` | CHANGE | Возвращает derived effectType, current reason display и cancellation metadata |
| `updateIncident` | CHANGE | Machine/author immutable; boundaries/effects/comment меняются с overlap validation |
| `deleteIncident` | CHANGE | `cancelManualIncident` выполняет idempotent soft cancel, физического удаления нет |
| `listIntervals` | CHANGE | `listMachineStateIntervals`: effective/calculated states, manual source, reason, quality, cursor filters |
| `getSummary` | CHANGE | Полные A/P/Q/OEE, state durations, coverage, diagnostics и previous-period metadata |
| `listCurrentStates` | REPLACE | `getEnterpriseOperationsOverview` возвращает line groups, counts и machine cards |
| `getLossesTimeseries` | CHANGE | Resolution, state durations, typed REASON/UNCLASSIFIED/OTHER и currency envelope |
| `getLossesPareto` | CHANGE | Typed rows, category/display metadata, totalLoss и cumulativeShare |
| `getChronometry` | CHANGE | Machine path, real shiftId/shiftDate; без shiftId возвращаются все occurrences local date |
| `getTelemetryLatest` | SPLIT | `getMachineOperationsCurrent` для featured values и `getMachineTelemetryCurrent` для каталога current values |
| `getTelemetrySeries` | CHANGE | UUID signalId, GAUGE/COUNTER point types, resolution и limit 720 |

## Удалённые модели старого draft

- `EnterpriseInput.shiftSchedule`: заменён самостоятельным ресурсом Shift.
- `MachineInput.opcTags`: протокольный OPC address принадлежит generator/adapter, а сигнал — отдельный `TelemetrySignal`.
- `MachineInput.stateRules` со строковым `rule`: заменён валидируемым AST `MachineStateConfiguration`.
- `MachineInput.thresholds`: цветовые UI thresholds не являются аналитической формулой и не входят в MVP domain API.
- `archived`: заменён единым `ACTIVE|DISABLED` без физического удаления.
- `LossReason.isPlanned`: удалён; plannedness выводится из effective state.
- `shift=all|day|night`: удалён; используется реальный UUID `shiftId`, а отсутствие фильтра означает все смены.
- JSON map `reasonId → seconds`: заменён массивом с `kind`, потому что `UNCLASSIFIED` и synthetic `OTHER` имеют разную семантику.

## Новые операции, отсутствовавшие в draft

- status-команды Enterprise, Shift, LossReason, Line, Machine, Component и TelemetrySignal;
- CRUD Shift;
- Machine create без Line и атомарное batch assignment;
- Component tree и reparent;
- TelemetrySignal configuration;
- StateConfiguration read/replace/validate;
- Machine activation readiness;
- Equipment tree;
- Machine operational current;
- Metric timeseries;
- ManualIncident close;
- State recalculation create/list/get.

## Сохранённые frontend-сценарии

Новый контракт сохраняет поиск и дерево оборудования, карточку машины, current state, паспорт, current telemetry, исторические graphs, KPI и сравнение, loss timeseries, Pareto, хронометраж, таблицу интервалов, регистрацию и журнал инцидентов, а также administration flow. Управление generator остаётся отдельным будущим API и не имитируется внутри Analytics API.
