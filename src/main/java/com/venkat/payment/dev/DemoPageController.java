package com.venkat.payment.dev;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.venkat.payment.api.PaymentNotFoundException;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.repository.PaymentRepository;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Developer demo controller serving an interactive test UI and ZXing QR image endpoint.
 */
@Controller
@RequestMapping("/dev")
@Profile("!prod & !production")
@ConditionalOnProperty(prefix = "payment.dev", name = "enabled", havingValue = "true")
public class DemoPageController {

    private static final Logger log = LoggerFactory.getLogger(DemoPageController.class);

    private final PaymentRepository paymentRepository;

    public DemoPageController(final PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping(value = "/pay-demo", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String demoPage() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>UPI QR Payment Simulator</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 24px; }
                        .container { max-width: 680px; margin: 0 auto; background: #1e293b; border-radius: 12px; padding: 28px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
                        h1 { margin-top: 0; font-size: 24px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }
                        .card { background: #0f172a; border: 1px solid #334155; border-radius: 8px; padding: 18px; margin-bottom: 20px; }
                        label { display: block; font-size: 13px; color: #94a3b8; margin-bottom: 6px; font-weight: 500; }
                        input { width: 100%; box-sizing: border-box; background: #1e293b; border: 1px solid #475569; color: #fff; padding: 10px 12px; border-radius: 6px; font-size: 14px; margin-bottom: 12px; }
                        button { cursor: pointer; border: none; border-radius: 6px; font-weight: 600; font-size: 14px; padding: 10px 16px; transition: all 0.2s; }
                        .btn-primary { background: #2563eb; color: #fff; width: 100%; }
                        .btn-primary:hover { background: #1d4ed8; }
                        .btn-sim { background: #334155; color: #f8fafc; margin-right: 8px; margin-bottom: 8px; }
                        .btn-sim:hover { background: #475569; }
                        .btn-success { background: #16a34a; }
                        .btn-danger { background: #dc2626; }
                        .btn-warning { background: #d97706; }
                        .status-badge { display: inline-block; padding: 6px 14px; border-radius: 9999px; font-weight: 700; font-size: 13px; text-transform: uppercase; }
                        .badge-PENDING { background: #854d0e; color: #fef08a; }
                        .badge-SUCCESS { background: #166534; color: #bbf7d0; }
                        .badge-FAILED { background: #991b1b; color: #fecaca; }
                        .badge-EXPIRED { background: #475569; color: #cbd5e1; }
                        .badge-CREATED { background: #1e40af; color: #bfdbfe; }
                        .qr-box { text-align: center; margin: 20px 0; padding: 16px; background: #fff; border-radius: 12px; display: inline-block; }
                        .qr-box img { display: block; width: 220px; height: 220px; }
                        .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px; margin-top: 14px; }
                        .info-item { background: #1e293b; padding: 8px 12px; border-radius: 6px; }
                        .info-item span { color: #94a3b8; display: block; font-size: 11px; margin-bottom: 2px; }
                        .log-box { max-height: 140px; overflow-y: auto; background: #090d16; border: 1px solid #1e293b; border-radius: 6px; padding: 10px; font-family: monospace; font-size: 12px; color: #38bdf8; margin-top: 16px; }
                    </style>
                </head>
                <body>
                <div class="container">
                    <h1>⚡ UPI QR Payment Service & Simulator</h1>
                    <p style="color: #94a3b8; font-size: 14px; margin-top: -6px; margin-bottom: 20px;">
                        End-to-end local testing with FakeGateway, cryptographic HMAC-SHA256 webhooks, and live status polling.
                    </p>

                    <!-- Create Payment Form -->
                    <div class="card">
                        <label for="orderId">Order ID</label>
                        <input type="text" id="orderId" value="ORDER-1001" placeholder="e.g. ORDER-1001">
                        
                        <label for="amount">Amount (INR)</label>
                        <input type="number" id="amount" value="500.00" step="0.01" min="1.00">
                        
                        <button class="btn-primary" onclick="createPayment()">Generate Dynamic UPI QR</button>
                    </div>

                    <!-- Payment Display -->
                    <div id="paymentSection" class="card" style="display:none; text-align: center;">
                        <div>
                            <span id="statusBadge" class="status-badge badge-PENDING">PENDING</span>
                        </div>
                        
                        <div class="qr-box">
                            <img id="qrImage" src="" alt="UPI QR Code">
                        </div>

                        <div class="info-grid" style="text-align: left;">
                            <div class="info-item"><span>Payment Reference</span><b id="dispRef">-</b></div>
                            <div class="info-item"><span>Payment ID</span><b id="dispId">-</b></div>
                            <div class="info-item"><span>Amount</span><b id="dispAmount">-</b></div>
                            <div class="info-item"><span>Expires At</span><b id="dispExpires">-</b></div>
                        </div>

                        <div style="margin-top: 24px; text-align: left;">
                            <label>Simulate Gateway Webhook:</label>
                            <div>
                                <button class="btn-sim btn-success" onclick="simulateResult('success')">Simulate Success</button>
                                <button class="btn-sim btn-danger" onclick="simulateResult('failed')">Simulate Failed</button>
                                <button class="btn-sim btn-warning" onclick="simulateResult('amount_mismatch')">Simulate Mismatch</button>
                                <button class="btn-sim" onclick="simulateResult('duplicate')">Simulate Duplicate</button>
                            </div>
                        </div>
                    </div>

                    <div class="log-box" id="logs">System ready. Create a payment to begin testing.</div>
                </div>

                <script>
                    let currentPaymentId = null;
                    let currentPaymentRef = null;
                    let pollInterval = null;

                    function log(msg) {
                        const box = document.getElementById('logs');
                        const time = new Date().toLocaleTimeString();
                        box.innerHTML = `[${time}] ${msg}<br>` + box.innerHTML;
                    }

                    let currentToken = null;

                    async function ensureToken() {
                        if (currentToken) return currentToken;
                        const res = await fetch('/dev/token?sub=customer-local-demo&scopes=payments:create,payments:read', { method: 'POST' });
                        const data = await res.json();
                        currentToken = data.access_token;
                        return currentToken;
                    }

                    async function createPayment() {
                        const orderId = document.getElementById('orderId').value;
                        const amount = parseFloat(document.getElementById('amount').value);

                        log(`Requesting authorization token...`);
                        const token = await ensureToken();

                        const idempotencyKey = 'DEMO-KEY-' + Date.now() + '-' + Math.random().toString(36).substring(2, 8);
                        log(`Creating payment for order ${orderId} of INR ${amount} (Idempotency: ${idempotencyKey})...`);
                        try {
                            const res = await fetch('/api/v1/payments', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'Authorization': 'Bearer ' + token,
                                    'Idempotency-Key': idempotencyKey
                                },
                                body: JSON.stringify({
                                    orderId: orderId,
                                    amount: amount,
                                    currency: 'INR',
                                    customerId: 'customer-local-demo'
                                })
                            });

                            if (!res.ok) {
                                const err = await res.json();
                                log(`Error creating payment: [${err.code}] ${err.message || res.statusText}`);
                                return;
                            }

                            const data = await res.json();
                            currentPaymentId = data.paymentId;
                            currentPaymentRef = data.paymentReference;

                            document.getElementById('paymentSection').style.display = 'block';
                            document.getElementById('dispRef').innerText = data.paymentReference;
                            document.getElementById('dispId').innerText = data.paymentId;
                            document.getElementById('dispAmount').innerText = data.currency + ' ' + data.amount;
                            document.getElementById('dispExpires').innerText = new Date(data.expiresAt).toLocaleTimeString();
                            document.getElementById('qrImage').src = '/dev/qr/' + encodeURIComponent(data.paymentReference);

                            updateStatusBadge(data.status);
                            log(`Payment created: Ref ${data.paymentReference}. Polling started.`);

                            if (pollInterval) clearInterval(pollInterval);
                            pollInterval = setInterval(pollPaymentStatus, 3000);
                        } catch (e) {
                            log(`Exception during payment creation: ${e.message}`);
                        }
                    }

                    async function pollPaymentStatus() {
                        if (!currentPaymentId) return;
                        try {
                            const token = await ensureToken();
                            const res = await fetch('/api/v1/payments/' + currentPaymentId, {
                                headers: {
                                    'Authorization': 'Bearer ' + token,
                                    'Cache-Control': 'no-store'
                                }
                            });
                            if (res.ok) {
                                const data = await res.json();
                                updateStatusBadge(data.status);
                                if (data.status === 'SUCCESS' || data.status === 'FAILED' || data.status === 'EXPIRED') {
                                    log(`Payment finalized with status: ${data.status}`);
                                }
                            }
                        } catch (e) {
                            console.error('Polling error', e);
                        }
                    }

                    function updateStatusBadge(status) {
                        const b = document.getElementById('statusBadge');
                        b.innerText = status;
                        b.className = 'status-badge badge-' + status;
                    }

                    async function simulateResult(resultType) {
                        if (!currentPaymentRef) {
                            log('No active payment to simulate!');
                            return;
                        }
                        log(`Posting simulator trigger: ${resultType} for ref ${currentPaymentRef}...`);
                        try {
                            const res = await fetch(`/dev/fake-gateway/${encodeURIComponent(currentPaymentRef)}/simulate?result=${resultType}`, {
                                method: 'POST'
                            });
                            const data = await res.json();
                            log(`Simulator outcome: [${data.status}] Webhook: ${data.webhookOutcome}`);
                            pollPaymentStatus();
                        } catch (e) {
                            log(`Simulator trigger failed: ${e.message}`);
                        }
                    }
                </script>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/qr/{paymentReference}", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> generateQrImage(@PathVariable("paymentReference") final String paymentReference) {
        final Optional<Payment> paymentOpt = this.paymentRepository.findByPaymentReference(paymentReference);
        if (paymentOpt.isEmpty()) {
            throw new PaymentNotFoundException("Payment not found for ref: " + paymentReference);
        }

        final String qrData = paymentOpt.get().getQrData();
        if (qrData == null || qrData.isBlank()) {
            throw new IllegalStateException("QR data not yet generated for payment: " + paymentReference);
        }

        try {
            final QRCodeWriter qrCodeWriter = new QRCodeWriter();
            final BitMatrix bitMatrix = qrCodeWriter.encode(qrData, BarcodeFormat.QR_CODE, 260, 260);
            final ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            final byte[] pngBytes = pngOutputStream.toByteArray();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(pngBytes);
        } catch (final Exception ex) {
            log.error("Failed to render ZXing QR image", ex);
            return ResponseEntity.internalServerError().build();
        }
    }
}

