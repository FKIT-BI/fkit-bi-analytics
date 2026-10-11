# Epic 3 implementation notes

Implemented the Analytics-side raw telemetry lifecycle and read projections:

- Release 007 provisions immutable, day-partitioned raw measurements, batch identity, signal first-observed/received timestamps, partition registry, and bounded retention routines.
- Release 008 adds durable machine recalculation work, lease/claim/finish functions, and retention blockers for pending work.
- Release 009 adds the current-signal projection and `/api/v1/machines/{machineId}/telemetry/current` read API.
- Release 010 adds state intervals/current state/deadlines, evaluator integration, and `/api/v1/machines/{machineId}/current-state`.
- Scheduled workers connect handoff, signal projection, state evaluation, and partition lifecycle. API contract is recorded in `docs/contracts/analytics-api-draft.yaml`; storage/hash details are in `docs/contracts/raw-telemetry-v1.md`.
- Targeted migration, evaluator, and golden-slice tests were added. The golden slice inserts at the committed raw-storage boundary because no Generator run/execution engine exists in the current codebase.

Not covered by this slice: end-to-end Generator Scenario/GenerationRun lifecycle (Epic 6). Historical state reconstruction, staging, and replay jobs are implemented in the Epic 4 Analytics work; the final Analytics and Generator suites passed together after Epic 4. Historical replay uses retained raw context and the current state configuration, with explicit bounded event/retention limits.
