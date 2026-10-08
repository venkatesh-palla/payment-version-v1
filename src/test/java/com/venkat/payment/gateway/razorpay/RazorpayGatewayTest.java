package com.venkat.payment.gateway.razorpay;

import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.gateway.model.RefundRequest;
import com.venkat.payment.gateway.model.RefundResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RazorpayGatewayTest {

    private static final String BASE_URL = "https://api.razorpay.com/v1";
    private static final String KEY_ID = "rzp_test_key123";
    private static final String KEY_SECRET = "rzp_test_secret456";
    private static final String WEBHOOK_SECRET = "webhook_secret_789";

    private RazorpayProperties properties;
    private MockRestServiceServer mockServer;
    private RazorpayClient client;
    private RazorpayStatusMapper statusMapper;
    private RazorpaySignatureVerifier signatureVerifier;
    private RazorpayGateway gateway;
    private Clock clock;

    @BeforeEach
    void setUp() {
        this.properties = new RazorpayProperties();
        this.properties.setBaseUrl(BASE_URL);
        this.properties.setKeyId(KEY_ID);
        this.properties.setKeySecret(KEY_SECRET);
        this.properties.setWebhookSecret(WEBHOOK_SECRET);
        this.properties.setTimeout(Duration.ofSeconds(5));

        final RestClient.Builder restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        this.client = new RazorpayClient(this.properties, restClientBuilder);
        this.statusMapper = new RazorpayStatusMapper();
        this.clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.signatureVerifier = new RazorpaySignatureVerifier(this.properties, this.clock);

        this.gateway = new RazorpayGateway(this.client, this.statusMapper, this.signatureVerifier);
    }

    @Test
    @DisplayName("Create payment POSTs to /payments/qr_codes with paise amount and returns UPI URI")
    void createPaymentSuccess() {
        final Instant expiresAt = this.clock.instant().plusSeconds(900);
        final CreatePaymentGatewayRequest request = new CreatePaymentGatewayRequest(
                "PAY-RZP-101", new BigDecimal("500.00"), "INR", "ORD-1", expiresAt
        );

        final String providerResponseJson = """
                {
                  "id": "qr_H5uukMG2U1bUfp",
                  "entity": "qr_code",
                  "status": "active",
                  "payment_amount": 50000,
                  "image_content": "upi://pay?pa=razorpay@icici&pn=Merchant&am=500.00&cu=INR&tn=PAY-RZP-101",
                  "close_by": %d,
                  "created_at": 1775654400
                }
                """.formatted(expiresAt.getEpochSecond());

        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess(providerResponseJson, MediaType.APPLICATION_JSON));

        final PaymentCreationResponse response = this.gateway.createPayment(request);

        assertThat(response.gatewayOrderId()).isEqualTo("qr_H5uukMG2U1bUfp");
        assertThat(response.qrData()).startsWith("upi://pay?");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        this.mockServer.verify();
    }

    @Test
    @DisplayName("Verify payment when captured queries QR payments and maps to SUCCESS")
    void verifyPaymentCapturedReturnsSuccess() {
        final String qrId = "qr_H5uukMG2U1bUfp";

        final String paymentsListJson = """
                {
                  "entity": "collection",
                  "count": 1,
                  "items": [
                    {
                      "id": "pay_K6vvkNH3V2cVgq",
                      "entity": "payment",
                      "amount": 50000,
                      "currency": "INR",
                      "status": "captured",
                      "method": "upi",
                      "created_at": 1775654500
                    }
                  ]
                }
                """;

        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId + "/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsListJson, MediaType.APPLICATION_JSON));

        final PaymentVerificationResponse verification = this.gateway.verifyPayment(qrId);

        assertThat(verification.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(verification.gatewayPaymentId()).isEqualTo("pay_K6vvkNH3V2cVgq");
        assertThat(verification.amount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(verification.currency()).isEqualTo("INR");
        assertThat(verification.paymentMethod()).isEqualTo("upi");
        this.mockServer.verify();
    }

    @Test
    @DisplayName("Verify payment when unpaid falls back to QR state active -> PENDING")
    void verifyPaymentUnpaidReturnsPending() {
        final String qrId = "qr_H5uukMG2U1bUfp";

        final String emptyPaymentsJson = """
                {
                  "entity": "collection",
                  "count": 0,
                  "items": []
                }
                """;

        final String qrJson = """
                {
                  "id": "qr_H5uukMG2U1bUfp",
                  "entity": "qr_code",
                  "status": "active",
                  "payment_amount": 50000
                }
                """;

        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId + "/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPaymentsJson, MediaType.APPLICATION_JSON));

        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(qrJson, MediaType.APPLICATION_JSON));

        final PaymentVerificationResponse verification = this.gateway.verifyPayment(qrId);

        assertThat(verification.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(verification.gatewayPaymentId()).isNull();
        this.mockServer.verify();
    }

    @Test
    @DisplayName("Unknown provider status maps strictly to PENDING, never SUCCESS")
    void unknownProviderStatusMapsToPending() {
        final PaymentStatus status = this.statusMapper.mapPaymentStatus("some_weird_unrecognized_status");
        assertThat(status).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Close payment sends POST to /payments/qr_codes/{id}/close")
    void closePaymentSendsClosePost() {
        final String qrId = "qr_H5uukMG2U1bUfp";

        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId + "/close"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"" + qrId + "\",\"status\":\"closed\"}", MediaType.APPLICATION_JSON));

        this.gateway.closePayment(qrId);
        this.mockServer.verify();
    }

    @Test
    @DisplayName("Refund calls POST to /payments/{id}/refund with paise amount")
    void refundSuccess() {
        final String paymentId = "pay_K6vvkNH3V2cVgq";
        final RefundRequest request = new RefundRequest(
                paymentId, new BigDecimal("250.00"), "INR", "PAY-RZP-101", "Order cancelled"
        );

        final String refundResponseJson = """
                {
                  "id": "rfnd_L7wwlOI4W3dWhr",
                  "entity": "refund",
                  "amount": 25000,
                  "currency": "INR",
                  "payment_id": "%s",
                  "status": "processed",
                  "receipt": "PAY-RZP-101",
                  "created_at": 1775654600
                }
                """.formatted(paymentId);

        this.mockServer.expect(requestTo(BASE_URL + "/payments/" + paymentId + "/refund"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(refundResponseJson, MediaType.APPLICATION_JSON));

        final RefundResponse response = this.gateway.refund(request);

        assertThat(response.refundId()).isEqualTo("rfnd_L7wwlOI4W3dWhr");
        assertThat(response.gatewayPaymentId()).isEqualTo(paymentId);
        assertThat(response.amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(response.status()).isEqualTo("processed");
        this.mockServer.verify();
    }

    @Test
    @DisplayName("Webhook signature verification succeeds on valid HMAC and rejects invalid")
    void webhookSignatureVerification() throws Exception {
        final String payload = "{\"event\":\"payment.captured\",\"payment\":{\"id\":\"pay_123\"}}";
        final byte[] rawBytes = payload.getBytes(StandardCharsets.UTF_8);

        // Compute valid signature
        final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final byte[] hmac = mac.doFinal(rawBytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : hmac) {
            final String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        final String validSig = hex.toString();

        assertThat(this.gateway.verifyWebhookSignature(rawBytes, validSig)).isTrue();
        assertThat(this.gateway.verifyWebhookSignature(rawBytes, "completely_invalid_signature")).isFalse();
    }

    @Test
    @DisplayName("Idempotent query retries on transient server failure")
    void idempotentQueryRetriesOnFailure() {
        final String qrId = "qr_H5uukMG2U1bUfp";

        // First attempt fails with 500
        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId + "/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // Second attempt succeeds
        final String paymentsListJson = """
                {
                  "entity": "collection",
                  "count": 1,
                  "items": [
                    {
                      "id": "pay_K6vvkNH3V2cVgq",
                      "entity": "payment",
                      "amount": 50000,
                      "currency": "INR",
                      "status": "captured",
                      "method": "upi",
                      "created_at": 1775654500
                    }
                  ]
                }
                """;
        this.mockServer.expect(requestTo(BASE_URL + "/payments/qr_codes/" + qrId + "/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsListJson, MediaType.APPLICATION_JSON));

        final PaymentVerificationResponse verification = this.gateway.verifyPayment(qrId);

        assertThat(verification.status()).isEqualTo(PaymentStatus.SUCCESS);
        this.mockServer.verify();
    }
}

