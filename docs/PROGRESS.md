# Level 1 Progress: QR-Based UPI Payment Service

Tracking progress for **Level 1 (INITIAL): Build the QR Payment Service From Scratch, Working End to End Locally**.

---

## Level 1 Roadmap & Status

- [x] **Step 1.1: Bootstrap**
  - Project skeleton: `pom.xml` (Java 21, Spring Boot 3.4.5, Spring JDBC, Validation, Actuator, PostgreSQL, Flyway, ZXing, Testcontainers, springdoc-openapi)
  - Spring Boot app entry point (`PaymentApplication.java`)
  - UTC `Clock` bean configuration (`ClockConfig.java`)
  - Validated configuration properties (`PaymentProperties.java`)
  - Environment profiles: `application.yml`, `application-local.yml`, `application-test.yml`
  - `docker-compose.yml` with PostgreSQL 15 only (healthcheck, named volume, port 5432)
  - `.env.example` with dummy environment defaults
  - `Makefile` with targets: `db-up`, `db-down`, `run`, `test`, `verify`
  - Tests green: `PaymentApplicationTest`, `ArchitectureTest`

- [x] **Step 1.2: Database (Flyway V1)**
  - Created `V1__init.sql` migration with explicit constraint/index names, `TIMESTAMPTZ`, and status checks
  - `payments` table with `id UUID PK`, `payment_reference UNIQUE`, `amount > 0`, `status IN (...)`, optimistic lock `version`, etc.
  - `payment_events` table for audit log and deduplication (`event_id UNIQUE`, `source`, `payload JSONB`, `processed`)
  - Indexed: `order_id`, `gateway_order_id`, `gateway_payment_id`, `status`, `expires_at`, `event_id`, `payment_id`

- [x] **Step 1.3: Domain + State Machine**
  - `PaymentStatus` enum (8 values: `CREATED`, `QR_GENERATED`, `PENDING`, `SUCCESS`, `FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`)
  - `PaymentStateMachine` single source of truth for all transitions
  - Self-transitions treated as harmless no-ops
  - Illegal transitions throw `InvalidStateTransitionException`
  - Methods: `canTransition(from, to)`, `validate(from, to)`, `allowedFromStates(to)`
  - Parameterized unit tests over full 8x8 matrix (all 64 state pairs) passing green

- [x] **Step 1.4: Gateway Abstraction + FakeGateway**
  - `PaymentGateway` interface: `gatewayName()`, `createPayment()`, `verifyPayment()`, `verifyWebhookSignature()`
  - Model records: `CreatePaymentGatewayRequest`, `PaymentCreationResponse`, `PaymentVerificationResponse`
  - `PaymentGatewayRegistry` dynamically discovers and routes by gateway identifier
  - `FakeGateway` implementation: in-memory `ConcurrentHashMap`, UPI string generator (`upi://pay?pa=fake@bank...`), HMAC-SHA256 signature verification in constant time (`MessageDigest.isEqual`), fallback to `PENDING` for unrecognized states
  - Unit tests for HMAC signatures (valid, tampered payload, invalid secret, null/missing) passing green

- [x] **Step 1.5: Create and Read Payment APIs**
  - `POST /api/v1/payments`:
    - `X-API-Key` authentication against `payment.api-key`
    - Validates orderId (not blank, max 64), amount (> 0, max 2 decimals), currency (in allow-list `INR`)
    - DB transaction 1: persist payment in `CREATED` state
    - Invokes `gateway.createPayment` outside open DB transactions
    - On gateway failure: marks `FAILED` and returns HTTP 503
    - DB transaction 2: updates gateway order id + QR string, moves `CREATED` -> `QR_GENERATED` -> `PENDING`
    - Returns HTTP 201 with `paymentId`, `paymentReference` (`PAY-` + 12 chars), `status: PENDING`, `qrCode`, `expiresAt`
  - `GET /api/v1/payments/{paymentId}`:
    - Returns `PaymentResponse` with `Cache-Control: no-store`
    - Throws `PaymentNotFoundException` (HTTP 404) if not found
  - `GlobalExceptionHandler` returning consistent `{ "code", "message", "timestamp" }` JSON envelope

- [x] **Step 1.6: Webhook (Verification & Deduplication)**
  - `POST /api/v1/payments/webhook/{gateway}`:
    - Strict raw bytes inspection before parsing
    - HMAC signature verification; rejects invalid with 401 (no payload dump)
    - JSON parsing to `WebhookPayload`; rejects malformed with 400
    - Deduplication: checks if `event_id` already processed; returns 200 without duplicate updates
    - Unknown payment: logs warning, records audit event, returns 200
    - Calls `gateway.verifyPayment(...)` outside open DB transactions; returns 503 if gateway down
    - Verifies amount, currency, and gateway order id; on mismatch flags `requires_manual_review = true` and records reason
    - Status decided strictly from verified gateway response (never webhook body)
    - Single atomic DB transaction: `ON CONFLICT (event_id) DO NOTHING` + status-guarded optimistic locking update + marks event processed
    - Handles late payments on `EXPIRED` orders by permitting `EXPIRED` -> `SUCCESS` with review flag
    - `onPaymentStatusChanged` hook invoked post-commit (ready for Level 2 outbox)
  - Unit tests covering all 10 edge cases passing green

- [x] **Step 1.7: Simulator + Demo Page (Profile local/test only)**
  - `POST /dev/fake-gateway/{paymentReference}/simulate?result=success|failed|pending|amount_mismatch|duplicate`
    - Updates FakeGateway in-memory order state
    - Crafts authentic signed webhook with HMAC-SHA256
    - Dispatches to real webhook pipeline
  - `GET /dev/pay-demo`: Interactive web UI with QR rendering, live status badge polling every 3 seconds, and simulation action buttons
  - `GET /dev/qr/{paymentReference}`: Serves raw PNG image of UPI QR generated via ZXing
  - `DevStartupLogger`: Logs loud startup warning; forbids loading under `prod` profile

- [x] **Step 1.8: Tests + Docs**
  - 100 comprehensive unit tests passing cleanly (`mvn test`)
  - Integration test suite (`PaymentWorkflowIT`) using Testcontainers PostgreSQL
  - Complete `README.md` with prerequisites, setup commands, cURL examples, and architecture map

---

## Level 1 Done Checklist

- [x] `mvn test` (100 unit tests) passes cleanly.
- [x] `make db-up && make run`, demo page at `http://localhost:8080/dev/pay-demo` completes simulated payments.
- [x] Invalid signature returns 401 and changes nothing.
- [x] Sending duplicate webhook twice updates payment once.
- [x] Amount mismatch does NOT mark SUCCESS and sets manual review flag.
- [x] No secrets committed; `.env.example` has dummy values only.
- [x] `docs/PROGRESS.md` is fully updated.
