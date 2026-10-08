# QR-Based UPI Payment Service: Complete Build Specification (Local-First, No Message Broker)

> **How to use this file**
> 1. Save it in your repo as `docs/AI_BUILD_SPEC.md` (or `CLAUDE.md` / `AGENTS.md` if your AI coding tool reads that automatically).
> 2. Tell your AI tool: *"Read docs/AI_BUILD_SPEC.md fully. Start with Phase 0 only. After each phase, stop and wait for my 'continue'."*
> 3. Fill the `<placeholders>` in Section 3 once. Everything else works as written.
> 4. Keep `docs/PROGRESS.md` updated (the AI must do this at the end of every phase) so a new chat can resume from the file alone.

---

## 0. ROLE AND WORKING AGREEMENT

You are a principal backend engineer with deep experience in fintech payments, Java, PostgreSQL, and distributed-systems reliability. You write production-grade code that is correct under concurrency, crashes, retries, and partial failures. You never invent payment-provider APIs.

**Rules of engagement**
- Work in the phases of Section 5. Complete ONE phase fully, run its exit criteria, then STOP and wait for my "continue".
- Output complete files with full paths. No `...`, no `TODO implement`. The only allowed gaps are lines marked `// INTEGRATION POINT: verify in provider docs`.
- Code must compile and tests must pass at the end of every phase. If you cannot run commands, say so and list the exact commands I should run.
- If a requirement is ambiguous, state your assumption in ONE line and proceed. Ask a question only if it blocks compilation.
- At the end of each phase output: **Delivered / Assumptions / Open integration points / How to verify (commands) / Next phase**, and update `docs/PROGRESS.md`.
- If you can inspect the repository, follow its existing conventions and make minimal changes to existing code. Otherwise use Section 4.
- Never refactor or rename things from earlier phases unless a bug requires it; if so, say why.

**Hard constraints (global)**
- DO NOT use RabbitMQ, SQS, Kafka, or any message broker. No Spring AMQP dependency, no broker containers, no broker config.
- DO NOT require any deployed server, cloud account, or paid service to build and test. Everything must run on a developer laptop.
- Keep the **transactional outbox**. Delivery goes through a pluggable `EventPublisher` interface so a broker adapter can be added later without changing core code (document the extension point; do not implement it).
- DO NOT use JPA/ORM. Use Spring JDBC.

---

## 1. BUSINESS CONTEXT

### 1.1 Flow
1. Our client app asks the payment service to collect money for an order.
2. For EVERY payment we create a NEW, unique, single-use dynamic UPI QR with a fixed amount, a unique gateway reference, and an expiry.
3. The customer scans the QR with any UPI app (PhonePe, Paytm, Google Pay, BHIM, bank apps) and pays.
4. All funds settle to ONE merchant bank account configured at the payment gateway (typically T+1/T+2). The bank account never identifies the payment. Matching is done ONLY by gateway order/QR ID + amount + our `payment_reference`.
5. The gateway sends a webhook. We verify the signature, then verify the real status directly with the gateway, then update our state.
6. We show the customer SUCCESS / FAILED / EXPIRED. The frontend is display-only and never trusted.
7. We emit an event (`PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `PAYMENT_REFUNDED`). A SEPARATE downstream system consumes it and runs the next business process.
8. THIS service owns payment truth only. It never runs downstream business logic beyond a stubbed example worker.

### 1.2 Non-goals
Card/netbanking flows, split settlement, multi-merchant routing, real downstream business logic, any message broker.

### 1.3 Core guarantees (must hold under all failures)
- **G1** One payment per idempotency key.
- **G2** A webhook/event is processed effectively once.
- **G3** A successful payment is never lost, even if the downstream is down.
- **G4** A successful payment is never downgraded to FAILED because downstream processing failed.
- **G5** Payment status and business-process status are separate concepts.
- **G6** Downstream processing is idempotent.
- **G7** The system recovers automatically after crash/restart.

---

## 2. LOCAL-FIRST DEVELOPMENT MODEL

I have NO servers. The project must be built and tested end to end on my machine.

**Runtime dependencies allowed locally:** JDK 21, Maven, and PostgreSQL 15+.

**PostgreSQL options (support both):**
- **Primary:** Docker Compose service `postgres` (`docker compose up -d postgres`).
- **Fallback if Docker is unavailable:** document how to run a locally installed PostgreSQL, and optionally support `io.zonky.test:embedded-postgres` for tests. Do NOT substitute H2 or any non-PostgreSQL database (we rely on `FOR UPDATE SKIP LOCKED`, `JSONB`, partial indexes, `ON CONFLICT`).

**Profiles**
| Profile | Purpose | Gateway | Auth |
|---|---|---|---|
| `local` (default for dev) | Daily development | `FakeGateway` | Local HS256 JWT (see 7) |
| `test` | Automated tests | `FakeGateway` / WireMock | Test JWTs |
| `sandbox` | Real gateway TEST credentials (later) | Real provider | Local JWT or real issuer |
| `live-smoke` | Real gateway LIVE, tiny amounts, manual real-app testing (later) | Real provider | Local JWT or real issuer |
| `prod` | Future production | Real provider | Real issuer |

**Safety:** application startup MUST FAIL if the fake gateway, dev endpoints, dev JWT secret, or demo page is enabled together with profile `prod` (or `live-smoke` when `max-amount` > 10.00 is configured).

**Everything required to test locally must be delivered:** FakeGateway, webhook simulator, demo page, local JWT minting, a fake downstream receiver, seed scripts, `Makefile` or `scripts/` helpers.

---

## 3. PLACEHOLDERS (fill once)

```
BASE_PACKAGE   = com.venkat.payment
GROUP_ID       = com.venkat
ARTIFACT_ID    = payment-service
GATEWAY (later)= FakeGateway
JWT_OWNER_CLAIM= sub
```

---

## 4. TECH STACK, CONVENTIONS, LAYOUT

### 4.1 Stack
Java 21, Spring Boot 3.x, Maven, PostgreSQL 15+, Spring JDBC (`NamedParameterJdbcTemplate` or `JdbcClient`), Flyway, Jackson, SLF4J + Logback (JSON logs via `logstash-logback-encoder`), Bean Validation, Spring Security OAuth2 Resource Server, ShedLock (JDBC provider), Resilience4j (retry, circuit breaker, time limiter), Micrometer + Actuator, springdoc-openapi, ZXing (QR rendering for the demo page), Spring `RestClient`.
Testing: JUnit 5, Mockito, AssertJ, Awaitility, Testcontainers (PostgreSQL only), WireMock, ArchUnit (layer rules), JaCoCo.

### 4.2 Persistence rules
- Plain SQL repositories with explicit `RowMapper`s. Java records for models/DTOs. Enums stored as `VARCHAR` with `CHECK` constraints.
- `@Transactional` only on service methods. NEVER call an external API (gateway, HTTP callback) while holding a DB transaction.
- Concurrency:
  - `version BIGINT` on `payments`; updates use `WHERE id=:id AND version=:version`.
  - Status-guarded updates: `UPDATE payments SET status=:new, version=version+1, updated_at=:now WHERE id=:id AND status IN (:allowedFrom)`; check rows affected; 0 rows means re-read and decide (already in target state = idempotent success; otherwise `INVALID_STATE_TRANSITION`).
- Dedup: `INSERT ... ON CONFLICT (event_id) DO NOTHING`; rows affected decides "new" vs "duplicate".
- Claiming work rows: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`.
- Schedulers: ShedLock with explicit `lockAtMostFor` and `lockAtLeastFor`, PLUS row-level `SKIP LOCKED`.
- Time: UTC only (`TIMESTAMPTZ`, `java.time.Instant`), inject a `Clock` bean so tests can control time.
- Money: `BigDecimal` in Java, `NUMERIC(19,2)` in DB. Never `double`/`float`.

### 4.3 Layout (use if the repo has no conventions)
```
src/main/java/<BASE_PACKAGE>/
  api/          controllers, request/response DTOs, GlobalExceptionHandler, CorrelationIdFilter
  service/      PaymentService, PaymentVerificationService, PaymentWebhookService,
                PaymentStateMachine, IdempotencyService, ReconciliationService,
                PaymentRecoveryService
  gateway/      PaymentGateway (interface), PaymentGatewayRegistry, model/ (neutral records),
                fake/ (FakeGateway + simulator), <provider>/ (ONLY provider-specific code)
  repository/   PaymentRepository, PaymentEventRepository, PaymentOutboxRepository,
                PaymentBusinessProcessRepository, IdempotencyKeyRepository,
                ConsumerProcessedEventRepository
  events/       EventPublisher (interface), InProcessEventPublisher, HttpCallbackEventPublisher,
                PaymentOutboxDispatcher, downstream/ (handler + BusinessProcessWorker),
                internal pull API controller
  scheduler/    PaymentExpiryScheduler, PaymentVerificationScheduler, OutboxDispatchScheduler,
                BusinessProcessScheduler, StuckPaymentRecoveryScheduler,
                ReconciliationScheduler, CleanupScheduler
  security/     SecurityConfig, WebhookSignatureVerifier, IpAllowListFilter, DevJwtConfig (local only)
  domain/       Payment, PaymentEvent, OutboxEvent, BusinessProcess, enums
  config/       PaymentProperties (@ConfigurationProperties, @Validated), beans, ProfileGuard
  exception/    PaymentException hierarchy + error codes
  dev/          (profiles local/test/sandbox/live-smoke ONLY) simulator controller, demo page, token minting
src/main/resources/db/migration/   V1__..., V2__...
src/test/java/...                  unit + integration (IT suffix, run by failsafe)
docs/  scripts/  docker-compose.yml  .env.example  Makefile
```
Rules: no business logic in controllers (validate, delegate, map). Services depend on interfaces (`PaymentGateway`, `EventPublisher`), never on provider classes. Enforce with ArchUnit tests.

---

## 5. PHASED DELIVERY

Each phase ends with its **exit criteria** passing. Do not start the next phase until I say "continue".

**Phase 0: Bootstrap**
`pom.xml` (dependency management, surefire + failsafe + JaCoCo), Spring Boot app class, profile files (`application.yml`, `-local`, `-test`), `docker-compose.yml` (PostgreSQL only, healthcheck, named volume), `.env.example`, `Makefile`/`scripts/` (`db-up`, `db-down`, `run`, `test`, `verify`), base Logback JSON config, `docs/PROGRESS.md`, ProfileGuard skeleton, ArchUnit skeleton.
*Exit:* `docker compose up -d postgres && mvn clean verify` passes; app starts and `/actuator/health` is UP.

**Phase 1: Architecture, schema, state machine**
Architecture explanation with Mermaid component diagram, ERD explanation (which constraint enforces which guarantee G1-G7), ALL Flyway migrations, enums, `PaymentStateMachine` with parameterized unit tests over the full transition matrix, repository tests against Testcontainers PostgreSQL.
*Exit:* migrations apply on an empty DB; state-machine tests pass.

**Phase 2: Gateway abstraction, FakeGateway, create/get payment APIs, local auth**
`PaymentGateway` interface, neutral models, registry, `FakeGateway`, `IdempotencyService`, `PaymentService`, `POST /api/v1/payments`, `GET /api/v1/payments/{id}`, local JWT security + token minting, validation, error handler skeleton, correlation ID filter.
*Exit:* curl flow works locally; idempotency replay and concurrent same-key tests pass.

**Phase 3: Webhook flow**
`WebhookSignatureVerifier`, webhook controller and service, event dedup, gateway verification, amount/currency/order checks, atomic payment + event + outbox write, late-success, out-of-order, refund handling, FakeGateway webhook signing, dev simulator endpoint.
*Exit:* simulator drives success/failed/duplicate/mismatch/late flows through the REAL webhook path; 20-thread duplicate-webhook test yields one state change and one outbox row.

**Phase 4: Outbox, EventPublisher, downstream handler**
`PaymentOutboxDispatcher`, `EventPublisher` (in-process + HTTP callback), pull API, downstream handler, `BusinessProcessWorker`.
*Exit:* success payment results in a COMPLETED business process exactly once, in both delivery modes (WireMock for callback mode).

**Phase 5: Schedulers**
Expiry, verification fallback, outbox dispatch, business-process worker, stuck-payment recovery, reconciliation (fake data source locally), cleanup. Multi-instance safe.
*Exit:* time-controlled tests (fixed `Clock`) pass; two-scheduler-instance test shows no double processing.

**Phase 6: Hardening**
Global error handling completion, structured logging with MDC, metrics, security hardening (rate limit, IP allow-list, body size, actuator on management port), configuration validation and fail-fast, ProfileGuard completion.
*Exit:* startup fails correctly for each unsafe profile/config combination (tested).

**Phase 7: Complete test suites**
Fill every gap in Section 18. Coverage targets met.
*Exit:* `mvn clean verify` green; coverage report generated.

**Phase 8: Local test kit and docs**
Demo page, scripts, `docs/` (README, TESTING_GUIDE, API docs, curl examples, sample webhooks, sequence diagrams, state diagram, runbook, frontend guide), OpenAPI export.
*Exit:* a new developer can clone, run `make run`, open the demo page, and complete a simulated payment in under 10 minutes.

**Phase 9 (only after I choose a gateway): Real provider adapter**
`gateway/<provider>` implementation, integration-point list, sandbox profile, tunnel guide, real-app test matrix, `live-smoke` profile.
*Exit:* sandbox webhook received and verified through a tunnel; matrix document ready.

