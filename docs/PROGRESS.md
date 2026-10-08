# Project Build Progress: QR-Based UPI Payment Service

Tracking progress per `docs/AI_BUILD_SPEC.md`.

## Current State Summary
- **Current Phase**: Phase 0 (Bootstrap) - COMPLETED
- **Next Phase**: Phase 1 (Architecture, schema, state machine)

---

## Phase Log

### Phase 0: Bootstrap
- [x] Clear prior multi-module skeleton
- [x] Configure single-module root `pom.xml` with dependencies (Java 21, Spring Boot 3.4.5, Spring JDBC, PostgreSQL, Flyway, ShedLock, Resilience4j, OpenAPI, ZXing, Logback JSON, Testcontainers, WireMock, ArchUnit, JaCoCo)
- [x] Create `PaymentApplication.java`
- [x] Configure profiles (`application.yml`, `application-local.yml`, `application-test.yml`)
- [x] Configure `docker-compose.yml` (PostgreSQL 16 only, healthcheck, named volume)
- [x] Create `.env.example`
- [x] Create `Makefile` and `scripts/` helpers (`db-up`, `db-down`, `run`, `test`, `verify`)
- [x] Configure Logback JSON logging (`logback-spring.xml`)
- [x] Implement `ProfileGuard` skeleton (fails startup on unsafe dev/fake in prod or live-smoke max-amount > 10.00)
- [x] Implement `ArchitectureTest` skeleton (ArchUnit rules)
- [x] Verify `mvn clean verify` passes with all tests green
- **Status**: COMPLETED

---

### Phase 1: Architecture, Schema, State Machine
- **Status**: PENDING

### Phase 2: Gateway Abstraction, FakeGateway, Create/Get APIs, Local Auth
- **Status**: PENDING

### Phase 3: Webhook Flow
- **Status**: PENDING

### Phase 4: Outbox, EventPublisher, Downstream Handler
- **Status**: PENDING

### Phase 5: Schedulers
- **Status**: PENDING

### Phase 6: Hardening
- **Status**: PENDING

### Phase 7: Complete Test Suites
- **Status**: PENDING

### Phase 8: Local Test Kit and Docs
- **Status**: PENDING

### Phase 9: Real Provider Adapter
- **Status**: PENDING
