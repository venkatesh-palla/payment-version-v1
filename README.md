# UPI QR Payment Service (payment-service)

A production-grade, local-first UPI dynamic QR payment service built with **Java 21**, **Spring Boot 3.4.5**, **Spring JDBC**, **PostgreSQL**, and **Flyway**. Designed to run completely on your laptop with zero external cloud dependencies and zero message brokers.

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
# Executes all 100 unit tests (State Machine matrix, FakeGateway HMAC, Webhook edge cases, Services)
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

## 3. Environment Variables

| Variable | Default (Local) | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payment_db` | PostgreSQL JDBC connection URL |
| `DATABASE_NAME` | `payment_db` | PostgreSQL database name |
| `DATABASE_USERNAME` | `payment_user` | PostgreSQL database username |
| `DATABASE_PASSWORD` | `payment_secret` | PostgreSQL database password |
| `PORT` | `8080` | Application HTTP port |
| `SPRING_PROFILES_ACTIVE` | `local` | Active Spring profile (`local`, `test`, `prod`) |
| `PAYMENT_GATEWAY` | `fake` | Selected payment provider (`fake`) |
| `APP_API_KEY` | `dev-api-key-12345` | API key required in `X-API-Key` header |
| `FAKE_GATEWAY_WEBHOOK_SECRET` | `fake-webhook-secret-key-12345` | Secret for HMAC-SHA256 webhook signatures |

---

## 4. Package & Folder Structure

```
src/main/java/com/venkat/payment/
├── PaymentApplication.java             # Spring Boot main entrypoint
├── api/
│   ├── CreatePaymentRequest.java       # Validated creation payload
│   ├── CreatePaymentResponse.java      # 201 response with QR data & expiry
│   ├── PaymentResponse.java            # Payment read response
│   ├── ErrorResponse.java              # Standard error envelope {code, message, timestamp}
│   ├── WebhookPayload.java             # Gateway webhook JSON payload
│   ├── PaymentController.java          # POST /api/v1/payments & GET /api/v1/payments/{id}
│   ├── PaymentWebhookController.java   # POST /api/v1/payments/webhook/{gateway}
│   ├── GlobalExceptionHandler.java     # Exception translations to standard error JSON
│   ├── PaymentNotFoundException.java
│   ├── PaymentGatewayUnavailableException.java
│   ├── UnauthorizedException.java
│   └── InvalidInputException.java
├── config/
│   ├── ClockConfig.java                # UTC system clock bean
│   └── PaymentProperties.java          # Type-safe validated configuration
├── domain/
│   ├── Payment.java                    # Payment domain entity
│   └── PaymentStatus.java              # CREATED, QR_GENERATED, PENDING, SUCCESS, FAILED, EXPIRED, CANCELLED, REFUNDED
├── gateway/
│   ├── PaymentGateway.java             # Core gateway interface
│   ├── PaymentGatewayRegistry.java     # Gateway lookup registry
│   ├── fake/FakeGateway.java           # In-memory simulator gateway with HMAC-SHA256
│   └── model/
│       ├── CreatePaymentGatewayRequest.java
│       ├── PaymentCreationResponse.java
│       └── PaymentVerificationResponse.java
├── repository/
│   ├── PaymentRepository.java          # Spring JDBC with optimistic locking & status guards
│   └── PaymentEventRepository.java     # Audit log & ON CONFLICT webhook deduplication
├── service/
│   ├── PaymentService.java             # Payment orchestration outside DB transactions
│   ├── PaymentWebhookService.java      # 11-step webhook pipeline with signature verification
│   ├── PaymentStateMachine.java        # Single source of truth for allowed state transitions
│   └── InvalidStateTransitionException.java
└── dev/
    ├── DevStartupLogger.java           # Startup warning for active dev endpoints
    ├── FakeGatewaySimulatorController.java # POST /dev/fake-gateway/{ref}/simulate
    └── DemoPageController.java         # GET /dev/pay-demo & GET /dev/qr/{ref}

src/main/resources/
├── db/migration/V1__init.sql          # Flyway migration: payments & payment_events
├── application.yml                    # Default application configuration
├── application-local.yml              # Local developer overrides
├── application-test.yml               # Test profile configuration
└── logback-spring.xml                 # Console log formatter with UTC timestamps
```

---

## 5. cURL Examples

### 1. Create Payment
```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-api-key-12345" \
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

### 2. Query Payment Status
```bash
curl -i http://localhost:8080/api/v1/payments/5fa23d14-87cf-4a37-b4db-f5068aa5a9cf
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

### 3. Send Signed Webhook (Manual HMAC-SHA256)
Compute HMAC-SHA256 signature using OpenSSL and POST to webhook URL:
```bash
SECRET="fake-webhook-secret-key-12345"
PAYLOAD='{"eventId":"evt_man_101","eventType":"payment.success","gatewayOrderId":"fake_ord_sample","gatewayPaymentId":"fake_pay_sample"}'

# Calculate HMAC-SHA256 hex signature:
SIGNATURE=$(echo -n "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')

# Post signed webhook:
curl -i -X POST http://localhost:8080/api/v1/payments/webhook/fake \
  -H "Content-Type: application/json" \
  -H "X-Signature: $SIGNATURE" \
  -d "$PAYLOAD"
```

### 4. Use Webhook Simulator (Automated)
```bash
curl -i -X POST "http://localhost:8080/dev/fake-gateway/PAY-A82F19X9KL22/simulate?result=success"
```
Options for `result`:
- `success`: Sets FakeGateway order to SUCCESS, generates valid signature, transitions payment to `SUCCESS`.
- `failed`: Sets FakeGateway order to FAILED, transitions payment to `FAILED`.
- `amount_mismatch`: Mismatches amount by +10.00, flags payment with `requires_manual_review = true`.
- `duplicate`: Re-sends same eventId to verify idempotent deduplication.
