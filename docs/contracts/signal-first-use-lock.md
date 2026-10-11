# Signal first-use locking

Analytics owns the durable `telemetry.signal_usage` marker. It records the first accepted raw use of a `signal_id`, independently of raw row retention.

Both the semantic configuration writer and every first-use telemetry writer coordinate on the same PostgreSQL transaction advisory key:

```sql
hashtextextended(signal_id::text, 0)
```

The configuration writer takes `pg_advisory_xact_lock(key)` before reading `signal_usage` and updating semantic fields. A telemetry writer takes `pg_try_advisory_xact_lock_shared(key)` before inserting the first raw record and the durable marker. If the try-lock is unavailable, the whole batch rolls back with a retryable contention result; it must not wait on this lock in the hot path. PostgreSQL holds either lock through transaction completion. Therefore a concurrent semantic update either commits before first use or observes the durable marker and returns `409 SIGNAL_SEMANTICS_LOCKED`.

Epic 3 writers must insert `telemetry.signal_usage(signal_id, first_observed_at, first_received_at)` in the same transaction as their first raw measurement. The marker has no expiry or deletion path. For migration compatibility, old `first_used_at` values are copied into the new timestamp columns; new writes use the measurement event time and PostgreSQL receipt time.
