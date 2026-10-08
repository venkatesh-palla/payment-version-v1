# Project Progress: QR-Based UPI Payment Service

Tracking progress for **Level 1 (INITIAL)** and **Level 2 (MEDIUM): Make It Reliable**.

---

## Level 1 Roadmap & Status (Completed)

- [x] **Step 1.1: Bootstrap**
- [x] **Step 1.2: Database (Flyway V1)**
- [x] **Step 1.3: Domain + State Machine**
- [x] **Step 1.4: Gateway Abstraction + FakeGateway**
- [x] **Step 1.5: Create and Read Payment APIs**
- [x] **Step 1.6: Webhook (Verification & Deduplication)**
- [x] **Step 1.7: Simulator + Demo Page (Profile local/test only)**
- [x] **Step 1.8: Tests + Docs**

---

## Level 2 Roadmap & Status (Completed)

- [x] **Step 2.1: Migration V2 (New Tables & Partial Indexes)**
  - `idempotency_keys` table with `(scope, idempotency_key)` PK, `request_hash`, `status`, `response_body JSONB`, `payment_id`
  - `payment_outbox` table with `sequence_no BIGSERIAL`, `event_id UNIQUE`, `status (PENDING/DELIVERED/FAILED)`, `retry_count`, `next_retry_at`
  - `payment_business_process` table with `status (PENDING/PROCESSING/COMPLETED/FAILED)`, `UNIQUE (payment_id, process_type)`
  - `consumer_processed_events` table for downstream deduplication with `(event_id, consumer_name)` PK
  - `shedlock` table for multi-instance scheduler synchronization
  - Partial unique index `uq_payments_active_order` ensuring at most one ACTIVE payment per order
  - Partial indexes `idx_payments_pending_expires_at` and `idx_payments_pending_next_verification_at`
- [x] **Step 2.2: Idempotent Create-Payment API**
  - Mandatory `Idempotency-Key` header with canonical SHA-256 payload validation
  - In-progress concurrent requests return 409 Conflict with `Retry-After: 2`
  - Duplicate requests with identical payload return cached 201 response
  - Reusing idempotency key with modified payload returns 422 Unprocessable Entity
  - Enforced single active payment per order via `uq_payments_active_order`
- [x] **Step 2.3: Mismatch Handling, Late Success, Review Flag**
  - Amount/currency mismatch flags payment with `requires_manual_review = true` and `review_reason`
  - Late success on expired payment transitions status with manual review flag
- [x] **Step 2.4: Transactional Outbox**
  - Status transitions and outbox event insertions execute inside the same ACID transaction
  - Deterministic event IDs (`<paymentId>:<EVENT_TYPE>`) eliminate duplicate outbox entries
- [x] **Step 2.5: Event Delivery Without a Broker**
  - In-process Spring Application Event delivery (`InProcessEventPublisher`)
  - Signed HTTP callback delivery (`HttpCallbackEventPublisher`) with HMAC signature and exponential backoff
  - Pull API (`InternalEventsController`) with sequence-based pagination (`/api/v1/internal/events`)
  - Periodic outbox polling dispatcher with ShedLock and `SKIP LOCKED`
- [x] **Step 2.6: Downstream Handler + Business Process Worker**
  - `DownstreamEventHandler` with atomic `consumer_processed_events` deduplication
  - `BusinessProcessWorker` executing background business tasks without downgrading payment status
- [x] **Step 2.7: Schedulers (Expiry, Verification, Dispatcher, Worker with ShedLock)**
  - `PaymentExpiryScheduler`: periodic expiration of overdue payments
  - `PaymentVerificationScheduler`: fallback polling for payments that missed webhooks
  - ShedLock JDBC distributed locking ensures safety in multi-instance environments
- [x] **Step 2.8: Local JWT Auth + Ownership**
  - Spring Security OAuth2 Resource Server with HS256 JWT validation
  - Role/scope authorization (`payments:write`, `payments:read`, `payments:internal`)
  - Customer ownership verification: customer callers can only read their own payments; 404 returned on customer mismatch to prevent enumeration
  - Local token minting utility via `scripts/token.sh` and `/dev/token`
- [x] **Step 2.9: Errors, Correlation IDs, Structured Logging**
  - Standardized error format with machine-readable codes and `correlationId`
  - CorrelationId filter injecting `X-Correlation-Id` into MDC and response headers
  - JSON log formatting with Logstash Logback encoder
- [x] **Step 2.10: Failure and Concurrency Tests**
  - 20 concurrent requests with same idempotency key create exactly 1 payment without errors
  - Outbox delivery retries on downstream HTTP failure and completes when restored
  - Downstream business process failure never impacts or downgrades payment SUCCESS
  - Expiry and verification schedulers tested with injectable fixed `Clock`

---

## Level 2 Done Checklist

- [x] `mvn clean test` passes (109 tests green), including concurrency, scheduler, outbox, and failure suites.
- [x] Same `Idempotency-Key` never creates two payments.
- [x] Same webhook twice never creates two events or two business processes.
- [x] Stopping callback receiver loses no events; restarting delivers them.
- [x] Payment missing webhook is fixed by verification scheduler.
- [x] Unpaid payments become EXPIRED automatically; paid ones are never expired.
- [x] Failed downstream processing never changes a SUCCESS payment.
- [x] JWT scopes and ownership work; webhook works without JWT.
- [x] Logs show correlation IDs and contain no secrets.
- [x] `docs/PROGRESS.md` updated; README updated with idempotency, outbox, schedulers, and JWT auth.

---

## Level 3 Roadmap & Status (In Progress)

- [x] **Step 3.1: Real Gateway Adapter (Razorpay Isolated)**
  - Extended `PaymentGateway` interface with `default void closePayment(String gatewayOrderId)` and `default RefundResponse refund(RefundRequest request)`.
  - Created isolated `com.venkat.payment.gateway.razorpay` package:
    - `RazorpayProperties`, `RazorpayClient` (Spring `RestClient`, Basic Auth, Resilience4j circuit breaker + retry, sanitized error logging).
    - DTOs: `RazorpayCreateQrRequest`, `RazorpayQrResponse`, `RazorpayPaymentListResponse`, `RazorpayPaymentItem`, `RazorpayRefundRequest`, `RazorpayRefundResponse`.
    - `RazorpayStatusMapper` (safe status mapping; unrecognized statuses map to `PENDING` with warning, never `SUCCESS`).
    - `RazorpaySignatureVerifier` (HMAC-SHA256 constant-time verification + configurable timestamp replay tolerance).
    - `RazorpayGateway` implementing `PaymentGateway` with paise conversion and dynamic single-use QR generation.
  - Documented `gateway/razorpay/INTEGRATION_POINTS.md`.
  - Added ArchUnit isolation test enforcing that classes outside `gateway.razorpay` cannot import Razorpay classes.
  - Unit tests with `MockRestServiceServer` verifying QR creation, status polling, QR closing, refund processing, and signature verification.
- [x] **Step 3.2: Cancel and Refund (Full Production Flow)**
  - `POST /api/v1/payments/{paymentId}/cancel`:
    - Checks gateway status first outside DB transaction. If customer paid (`status == SUCCESS`), transitions to `SUCCESS` and throws 409 `InvalidStateTransitionException`.
    - If unpaid, closes gateway QR and transitions payment to `CANCELLED`, publishing `PAYMENT_CANCELLED` transactional outbox event.
    - Authorized for scopes `payments:cancel`, `payments:write`, or `payments:internal`.
  - `POST /api/v1/payments/{paymentId}/refund`:
    - Requires `payments:internal` scope and mandatory `Idempotency-Key` header under `PAYMENT_REFUND` scope.
    - Full refund support (`refundAmount == payment.amount`); validates payment is in `SUCCESS` status.
    - Calls `gateway.refund()` outside DB transaction, updates payment status to `REFUNDED`, records `refund_id`, and publishes `PAYMENT_REFUNDED` outbox event atomically.
  - Webhook refund handling (`payment.refunded` / `refund.processed`) with deduplication against double-processing.
  - Integration tests in `PaymentServiceTest` and `PaymentControllerTest` covering all cancel/refund scenarios and error cases.
- [ ] **Step 3.3: Stuck-Payment Recovery, Reconciliation, and Cleanup Jobs**
- [ ] **Step 3.4: Security Hardening & Startup Profile Guards**
- [ ] **Step 3.5: Metrics, Alerts & Production Runbook**
- [ ] **Step 3.6: Real UPI App Test Kit & Sandbox Setup**
- [ ] **Step 3.7: Final End-to-End Suite & Code Coverage Verification**
- [ ] **Step 3.8: Production Documentation & Mermaid Diagrams**

