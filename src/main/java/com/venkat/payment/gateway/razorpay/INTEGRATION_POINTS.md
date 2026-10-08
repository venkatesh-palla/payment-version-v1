# Razorpay Gateway Integration Points & Verifications

This document tracks all integration points, documented behaviors, and provider assumptions for the Razorpay UPI QR integration.

---

## 1. Documented & Verified APIs

1. **Dynamic Single-Use QR Creation**
   - **Endpoint:** `POST https://api.razorpay.com/v1/payments/qr_codes`
   - **Auth:** HTTP Basic Auth (`key_id:key_secret`)
   - **Fields Verified:**
     - `type: "upi_qr"`
     - `usage: "single_use"`
     - `fixed_amount: true`
     - `payment_amount`: Amount in Paise (e.g. ₹500.00 = `50000`)
     - `close_by`: Epoch timestamp in seconds when the QR code should automatically close/expire.
     - `image_content`: UPI intent string (`upi://pay?...`) returned in response.

2. **QR Status & Payment Query**
   - **Endpoint:** `GET https://api.razorpay.com/v1/payments/qr_codes/{qr_id}/payments`
   - **Fields Verified:**
     - `items[0].id`: Payment ID (`pay_...`)
     - `items[0].status`: `captured` / `authorized`
     - `items[0].amount`: Amount received in Paise
     - `items[0].created_at`: Epoch timestamp in seconds

3. **QR Close API**
   - **Endpoint:** `POST https://api.razorpay.com/v1/payments/qr_codes/{qr_id}/close`
   - **Behavior:** Explicitly closes an active QR code so customers cannot make late or accidental payments after expiry or cancellation.

4. **Refund API**
   - **Endpoint:** `POST https://api.razorpay.com/v1/payments/{payment_id}/refund`
   - **Fields Verified:**
     - `amount`: Refund amount in Paise
     - `receipt`: Unique merchant identifier (our `payment_reference`) for idempotency
     - `id`: Unique refund identifier (`rfnd_...`)

5. **Webhook Signature Verification**
   - **Header:** `X-Razorpay-Signature`
   - **Algorithm:** HMAC-SHA256 of raw webhook body bytes using the configured Webhook Secret.
   - **Comparison:** Constant-time comparison implemented to prevent timing analysis attacks.

---

## 2. Integration Points Requiring Provider Environment Verification

1. **Webhook Replay Timestamp Header:**
   - `// INTEGRATION POINT: verify in provider docs`
   - Razorpay delivers webhook event JSON containing `created_at` (epoch seconds). Certain webhook configurations or proxy setups may also transmit `X-Razorpay-Event-Time`. Our `RazorpaySignatureVerifier.verifyTimestamp` validates timestamps within `payment.gateway.razorpay.replay-tolerance` (default 5 minutes).

2. **UPI Intent URI Property:**
   - In QR responses, `image_content` supplies the raw string formatted as `upi://pay?...` ready for QR scanning. Confirm whether custom merchant parameters (e.g., `pn`, `pa`) need specific dashboard enablement.

3. **Settlement and Reconciliation Download API:**
   - Razorpay offers Settlement and Payment Reconciliation reports via `/v1/settlements` and `/v1/payments?from=...&to=...`. Verify whether the settlement webhook `settlement.processed` or batch CSV export is preferred for daily reconciliations.

