# Story 1.1 verification — Analytics

- Spring Boot parent: 3.5.16; Java target remains 21. JDBC and existing migration configuration remain intact.
- Added bounded `X-Trace-Id` ingress handling, common `ErrorResponse`, safe validation and unexpected-error responses, and process liveness/readiness probes.
- `docker run --rm -v fkit-bi-maven-cache:/root/.m2 -v "$PWD:/workspace" -w /workspace maven:3.9-eclipse-temurin-21 ./mvnw verify -q` — passed on 2026-10-10 (Java 21.0.12.1, Spring Boot 3.5.16; nine HTTP/application tests passed).
- HTTP coverage: valid, missing, invalid and oversized trace IDs, malformed JSON, validation, sanitized 500, and Actuator liveness/readiness including readiness refusal (503).
