# Raw telemetry database contract v1

Analytics release 007 owns the DDL. Generator writes only `telemetry.ingest_batch`,
`telemetry.raw_measurement`, `telemetry.signal_usage`, and
`telemetry.recalculation_hint` using one short PostgreSQL transaction per batch.
Contract details for validation and retry are in
`fkit-bi-infra/docs/bmad/specs/spec-raw-telemetry-ingestion/writer-protocol.md`.

## Batch identity and encoding

Identity is `(producer_id, producer_batch_id)`. The SHA-256 payload bytes start with
the 18 ASCII bytes `TELEMETRY_BATCH_V1`, followed by producer id and batch id as
UTF-8 with unsigned 32-bit big-endian byte lengths, producer sequence as signed
64-bit big-endian, optional run UUID as a one-byte presence marker plus 16 network
order bytes, mode as signed 16-bit big-endian (`1=REALTIME`, `2=HISTORICAL`), then
measurement count as unsigned 32-bit big-endian. Measurements keep their input
ordinal order. Each has UUID (two big-endian 64-bit words), epoch milliseconds as
signed 64-bit, type code as one byte (`1=DOUBLE`, `2=LONG`, `3=BOOLEAN`, `4=STRING`),
typed value (IEEE-754 binary64, signed 64-bit, one `0/1` byte, or UTF-8 with unsigned
32-bit byte length), and source quality as signed 16-bit (`0=GOOD`, `1=UNCERTAIN`,
`2=BAD`). `NaN`, infinity, and sub-millisecond instants are invalid. Missing source
quality is normalized to GOOD before hashing.

An identical retry is a success even if raw retention has since removed its day. A
different hash under the same identity is a conflict. An insert error at any point
rolls back batch header, first-use markers, all raw rows, and hints together.

## Raw and lifecycle rules

`raw_measurement` is append-only and RANGE-partitioned by UTC day of `observed_at`.
It has no row-level primary/unique key, foreign keys, or payload indexes. Exactly one
typed value column is non-null; flags use bit 1 for out-of-range and bit 2 for
future clock skew. Signal quality remains a separate source value. The only parent
index is `(signal_id, observed_at)`.

Writers acquire nonblocking shared transaction advisory locks for signal semantics
and each UTC day, then require an `ACTIVE` registry row before inserting. Lock keys
are provided by `hashtextextended(signal_id::text, 0)` and
`telemetry.raw_partition_day_lock_key(date)`. Registry lock contention is retryable.
DDL is kept out of writer transactions. Partition creation is restricted to UTC
today−89 through today+7. Retirement uses a nonblocking exclusive day lock and
bounded lock/statement timeouts; unconsumed hints block deletion. The blocker
function is an extension seam for future durable work/leases/publication tables.

`signal_usage` is a durable first-use marker and survives raw retention. Signal
configuration writers take the corresponding exclusive transaction advisory lock;
telemetry writers take the shared variant and add a missing marker in the same
transaction as the measurement.
