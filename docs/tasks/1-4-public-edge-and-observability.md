# Evidence: Story 1.4 — Analytics

- Added the Prometheus Micrometer registry and Actuator scrape endpoint; it remains private behind the Compose network and is blocked by Nginx on public paths.
- Configured HTTP histogram metrics, service tags, JSON console logs, and `traceId` in MDC while preserving the existing 128-character trace-ID contract.
- Added filter and anonymous scrape/readiness tests.
- Verification: `mvn -B verify` passed (30 tests, run through the Maven/Java 21 container because Java is not installed on the host).
- Domain ingestion/calculation custom metrics remain unavailable because those operations are not implemented in this service yet.
