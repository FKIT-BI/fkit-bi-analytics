# Signal first-use locking

Analytics owns the durable `telemetry.signal_usage` marker. It records the first accepted raw use of a `signal_id`, independently of raw row retention.

Both the semantic configuration writer and every first-use telemetry writer coordinate on the same PostgreSQL transaction advisory key:

```sql
hashtextextended(signal_id::text, 0)
```

The configuration writer takes `pg_advisory_xact_lock(key)` before reading `signal_usage` and updating semantic fields. A telemetry writer takes `pg_advisory_xact_lock_shared(key)` before inserting the first raw record and the durable marker. PostgreSQL holds either lock through transaction completion. Therefore a concurrent semantic update either commits before first use or observes the durable marker and returns `409 SIGNAL_SEMANTICS_LOCKED`.

Epic 3 writers must insert `telemetry.signal_usage(signal_id)` in the same transaction as their first raw measurement. The marker has no expiry or deletion path.
