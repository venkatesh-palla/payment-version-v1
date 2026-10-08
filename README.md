# UPI QR Payment Service (payment-service)

A production-grade, local-first UPI dynamic QR payment service built with **Java 21**, **Spring Boot 3.4.5**, **Spring JDBC**, **PostgreSQL**, and **Flyway**. Hardened for reliability with idempotency keys, transactional outbox, downstream business process workers, automated fallback schedulers, and JWT authentication with ownership checks. Designed to run completely on your laptop with zero external cloud dependencies and zero message brokers.

---

## 1. Prerequisites
- **JDK 21**
- **Maven 3.9+** (or included `./mvnw`)
- **Docker & Docker Compose** (for PostgreSQL 15)

---

## 2. Quickstart

### 1. Start Local Database
```bash
make db-up
# Starts PostgreSQL 15 on port 5432 and waits until healthy
```

### 2. Run Test Suite
```bash
make test
# Executes all 109 automated unit, concurrency, scheduler, failure, and security tests
```

### 3. Run Locally
```bash
make run
# Starts the Spring Boot app on port 8080 with profile 'local'
```

### 4. Interactive Demo UI
Open your browser to:
```
http://localhost:8080/dev/pay-demo
```
- Generate dynamic single-use UPI QR codes.
- Scan or view real-time polling updates every 3 seconds.
- Click simulation buttons to trigger authentic signed webhooks (`success`, `failed`, `amount_mismatch`, `duplicate`).

---

## 3. Level 2 Reliability Features

### 1. Idempotent Payment Creation
- Every `POST /api/v1/payments` requires an `Idempotency-Key` HTTP header.
- A canonical SHA-256 hash of the request body is stored with a unique composite key `(scope, idempotency_key)`.
- Concurrent in-flight requests return `HTTP 409 Conflict` with `Retry-After: 2`.
- Subsequent identical requests return the cached `HTTP 201 Created` response.
- Reusing an existing key with a modified payload returns `HTTP 422 Unprocessable Entity`.
- Only **one active payment per order** is permitted at any given time (enforced via partial unique index).

### 2. Transactional Outbox Pattern
- Every payment status change updates the `payments` table and writes a domain event to `payment_outbox` in the **same atomic database transaction**.
- Deterministic event IDs (`<paymentId>:<EVENT_TYPE>`) eliminate duplicate outbox entries.
- Events are dispatched asynchronously via:
  1. **In-process Spring Events** (`InProcessEventPublisher`).
  2. **Signed HTTP Callbacks** (`HttpCallbackEventPublisher`) with HMAC-SHA256 headers, exponential backoff, and retry jitter.
  3. **Pull API** (`GET /api/v1/internal/events?afterSequenceNo=...`) with sequence-based pagination for external consumers.

### 3. Downstream Worker & Isolation
- Downstream events are deduplicated via atomic `consumer_processed_events` records.
- Initiates business processes (e.g. `ORDER_FULFILLMENT`) handled by background worker tasks.
- Downstream process errors or exhausted retries **never downgrade or impact** an authoritative payment's `SUCCESS` status.

### 4. Resilient Schedulers (Multi-Instance Safe via ShedLock)
- **Payment Expiry Scheduler**: Periodically identifies expired unpaid payments, checks gateway once, and marks them `EXPIRED` with transactional outbox emission.
- **Payment Verification Scheduler**: Catches pending payments that missed webhooks, polling the gateway with gentle exponential backoff and transitioning confirmed payments to `SUCCESS`.
- **Payment Outbox Dispatcher**: Sweeps pending outbox events every 2 seconds with `SKIP LOCKED`.
- All schedulers use `ShedLock` over JDBC, ensuring zero duplicate executions across multiple service instances.

### 5. Local JWT Authentication & Ownership Checks
- Secures payment endpoints with HS256 JWT tokens.
- Required scopes: `payments:write` to create payments, `payments:read` to inspect payments, `payments:internal` to read any payment or pull outbox events.
- **Ownership verification**: Customer tokens can only read payments belonging to their `customerId`. Calls targeting another customer's payment return `HTTP 404 Not Found` to prevent account enumeration.
- Webhook callbacks remain accessible without JWTs, secured by raw-byte HMAC-SHA256 signatures.

---

## 4. Minting Development JWT Tokens

Generate tokens for local testing using the provided script:
```bash
# Mint a token for customer "CUST-101" with read & write permissions:
./scripts/token.sh --sub CUST-101 --scopes payments:read,payments:write

# Mint an internal admin token:
./scripts/token.sh --sub ADMIN-SERVICE --scopes payments:internal,payments:read,payments:write
```

Alternatively, call the local developer endpoint (available in `local` and `test` profiles):
```bash
curl -i -X POST http://localhost:8080/dev/token \
  -H "Content-Type: application/json" \
  -d '{"subject":"CUST-101","scopes":["payments:read","payments:write"]}'
```

---

## 5. cURL Examples

### 1. Create Payment (Idempotent & Authenticated)
```bash
TOKEN=$(./scripts/token.sh --sub cust-user-1 --scopes payments:write,payments:read)

curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: KEY-$(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORDER-101",
    "amount": 500.00,
    "currency": "INR",
    "customerId": "cust-user-1"
  }'
```
**Response (HTTP 201 Created):**
```json
{
  "paymentId": "5fa23d14-87cf-4a37-b4db-f5068aa5a9cf",
  "paymentReference": "PAY-A82F19X9KL22",
  "status": "PENDING",
  "amount": 500.00,
  "currency": "INR",
  "qrCode": "upi://pay?pa=fake@bank&pn=FakeMerchant&am=500.00&cu=INR&tn=PAY-A82F19X9KL22",
  "expiresAt": "2026-10-08T12:45:00Z"
}
```

### 2. Query Payment Status (Ownership Guarded)
```bash
curl -i http://localhost:8080/api/v1/payments/5fa23d14-87cf-4a37-b4db-f5068aa5a9cf \
  -H "Authorization: Bearer $TOKEN"
```
**Response (HTTP 200 OK with `Cache-Control: no-store`):**
```json
{
  "paymentId": "5fa23d14-87cf-4a37-b4db-f5068aa5a9cf",
  "paymentReference": "PAY-A82F19X9KL22",
  "orderId": "ORDER-101",
  "status": "PENDING",
  "amount": 500.00,
  "currency": "INR",
  "paidAt": null,
  "expiresAt": "2026-10-08T12:45:00Z"
}
```

### 3. Pull Internal Outbox Events
```bash
INTERNAL_TOKEN=$(./scripts/token.sh --sub erp-consumer --scopes payments:internal)

curl -i "http://localhost:8080/api/v1/internal/events?afterSequenceNo=0&limit=50" \
  -H "Authorization: Bearer $INTERNAL_TOKEN"
```

---

## 6. Environment Variables

| Variable | Default (Local) | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payment_db` | PostgreSQL JDBC connection URL |
| `DATABASE_NAME` | `payment_db` | PostgreSQL database name |
| `DATABASE_USERNAME` | `payment_user` | PostgreSQL database username |
| `DATABASE_PASSWORD` | `payment_secret` | PostgreSQL database password |
| `PORT` | `8080` | Application HTTP port |
| `SPRING_PROFILES_ACTIVE` | `local` | Active Spring profile (`local`, `test`, `prod`) |
| `PAYMENT_GATEWAY` | `fake` | Selected payment provider (`fake`) |
| `DEV_JWT_SECRET` | `payment-secret-local-hmac-key-min-256-bits-ok!` | HS256 secret for local JWT signing |
| `FAKE_GATEWAY_WEBHOOK_SECRET` | `fake-webhook-secret-key-12345` | Secret for HMAC-SHA256 webhook signatures |
| `PAYMENT_EVENTS_CALLBACK_URL` | `http://localhost:8080/dev/fake-downstream/webhook` | HTTP webhook target for outbox callback delivery |
