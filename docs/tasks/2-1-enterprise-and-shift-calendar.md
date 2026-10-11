# Story 2.1: Enterprise and shift calendar

Status: implemented; targeted verification passed on Java 21 and PostgreSQL Testcontainers.

## Delivered

- Added Analytics Liquibase release `003-enterprise-shifts` with normalized Enterprise and Shift tables, stable-code uniqueness, ownership foreign keys, validity/status checks, and listing indexes.
- Added authenticated `/api/v1` Enterprise and Shift CRUD/status/list endpoints with DTO validation, default ACTIVE filtering, stable order, page metadata, and `Location` on create.
- Serialized Shift create/update overlap validation with `SELECT ... FOR UPDATE` on the owning Enterprise. Overlap checks include disabled shifts, validity dates, cross-midnight intervals, and 24-hour shifts.
- Added Enterprise timezone validation and a transactional timezone lock check for temporal records when the corresponding telemetry, incident, generator, or analytics tables are present.
- Added used Shift boundary/validFrom lock checks and `validTo` history protection, plus structured `SHIFT_SCHEDULE_LOCKED` and overlap/code conflict errors.
- Added a pure event-time occurrence resolver with half-open boundaries, shift-start local dates, and deterministic DST gap/overlap policy.
- Added targeted resolver and PostgreSQL-backed API tests. The existing normative `docs/contracts/analytics-api-draft.yaml` already contained these operation paths and DTOs, so it required no path/schema additions.
- Verified rejected timezone changes after signal usage, machine-owned incident and analytics projection data; invalid timezone, duplicate Enterprise code, used schedule boundary/validity locks, disabled filtering, literal LIKE search, pagination metadata, and serialized concurrent overlapping schedule creation.

## Verification

- `git diff --check` — passed.
- `JAVA_HOME=/home/dds/.jdks/corretto-21.0.11 PATH=/home/dds/.jdks/corretto-21.0.11/bin:$PATH ./mvnw -B -Dmaven.repo.local=/tmp/m2-story-2-1 -Dtest=ShiftCalendarTest,EnterpriseShiftApiTest test` — passed, 14 tests, 0 failures/errors/skips. Includes Liquibase migration execution in PostgreSQL 16 Testcontainers.
- Full Maven `verify` not run; the targeted build compiled production code and the complete test source set before running the 14 focused tests.

## Remaining verification risk

The temporal lock probe checks the currently agreed table candidates and their owner columns dynamically. When Epic 3–5 tables are introduced, confirm their final table/column names are included. Full-suite regression verification remains for CI; this story's migration and focused API/domain scenarios passed locally.
