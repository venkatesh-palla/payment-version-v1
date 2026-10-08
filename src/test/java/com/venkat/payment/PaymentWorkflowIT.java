package com.venkat.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.CreatePaymentRequest;
import com.venkat.payment.api.CreatePaymentResponse;
import com.venkat.payment.api.PaymentResponse;
import com.venkat.payment.api.WebhookPayload;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.security.JwtTokenService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PaymentWorkflowIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("payment_test_db")
            .withUsername("payment_user")
            .withPassword("payment_secret");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("payment.scheduler.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentProperties paymentProperties;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private String baseUrl() {
        return "http://localhost:" + this.port;
    }

    private HttpHeaders authHeaders(final String customerId, final String idempotencyKey) {
        final String token = this.jwtTokenService.generateToken(customerId, List.of("payments:write", "payments:read"), 3600);
        final HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("End-to-end integration: create payment -> simulate success -> status becomes SUCCESS")
    void fullPaymentSuccessWorkflow() throws Exception {
        final String customerId = "CUST-IT-1";
        final String idempotencyKey = "IDEMP-IT-100";

        // 1. Create Payment
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-IT-100", new BigDecimal("500.00"), "INR", customerId);
        final HttpHeaders headers = authHeaders(customerId, idempotencyKey);

        final ResponseEntity<CreatePaymentResponse> createRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                CreatePaymentResponse.class
        );

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        final CreatePaymentResponse created = createRes.getBody();
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(created.paymentReference()).startsWith("PAY-");

        // 2. Trigger simulator success
        final ResponseEntity<Map> simRes = this.restTemplate.postForEntity(
                baseUrl() + "/dev/fake-gateway/" + created.paymentReference() + "/simulate?result=success",
                null,
                Map.class
        );
        assertThat(simRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simRes.getBody().get("status")).isEqualTo("SIMULATED");

        // 3. Verify payment query returns SUCCESS
        final ResponseEntity<PaymentResponse> getRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments/" + created.paymentId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(customerId, null)),
                PaymentResponse.class
        );
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRes.getHeaders().getCacheControl()).isEqualTo("no-store");
        final PaymentResponse finalPayment = getRes.getBody();
        assertThat(finalPayment).isNotNull();
        assertThat(finalPayment.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(finalPayment.paidAt()).isNotNull();
    }

    @Test
    @DisplayName("Duplicate webhook delivered twice modifies payment status exactly once")
    void duplicateWebhookChangesStateOnce() throws Exception {
        final String customerId = "CUST-IT-2";
        final String idempotencyKey = "IDEMP-IT-DUP";

        // 1. Create Payment
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-IT-DUP", new BigDecimal("750.00"), "INR", customerId);
        final HttpHeaders headers = authHeaders(customerId, idempotencyKey);

        final ResponseEntity<CreatePaymentResponse> createRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                CreatePaymentResponse.class
        );
        final CreatePaymentResponse created = createRes.getBody();
        assertThat(created).isNotNull();

        // 2. Send simulator duplicate twice
        final ResponseEntity<Map> simRes1 = this.restTemplate.postForEntity(
                baseUrl() + "/dev/fake-gateway/" + created.paymentReference() + "/simulate?result=duplicate",
                null,
                Map.class
        );
        assertThat(simRes1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simRes1.getBody().get("webhookOutcome")).isEqualTo("processed");

        final ResponseEntity<Map> simRes2 = this.restTemplate.postForEntity(
                baseUrl() + "/dev/fake-gateway/" + created.paymentReference() + "/simulate?result=duplicate",
                null,
                Map.class
        );
        assertThat(simRes2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simRes2.getBody().get("webhookOutcome")).isEqualTo("duplicate");

        // Check payment status is still SUCCESS
        final ResponseEntity<PaymentResponse> getRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments/" + created.paymentId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(customerId, null)),
                PaymentResponse.class
        );
        assertThat(getRes.getBody().status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("Invalid signature webhook returns 401 and does not modify payment")
    void invalidSignatureFailsAndChangesNothing() throws Exception {
        final String customerId = "CUST-IT-3";
        final String idempotencyKey = "IDEMP-IT-BADSIG";

        // 1. Create Payment
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-IT-BADSIG", new BigDecimal("300.00"), "INR", customerId);
        final HttpHeaders headers = authHeaders(customerId, idempotencyKey);

        final ResponseEntity<CreatePaymentResponse> createRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                CreatePaymentResponse.class
        );
        final CreatePaymentResponse created = createRes.getBody();
        assertThat(created).isNotNull();

        // 2. Send webhook with tampered / bad signature
        final WebhookPayload payload = new WebhookPayload("evt_bad_sig_1", "payment.success", "fake_ord_unknown", "fake_pay_1");
        final byte[] rawBytes = this.objectMapper.writeValueAsBytes(payload);

        final HttpHeaders webhookHeaders = new HttpHeaders();
        webhookHeaders.set("X-Signature", "completely_invalid_hex_signature");
        webhookHeaders.setContentType(MediaType.APPLICATION_JSON);

        final ResponseEntity<Map> webhookRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments/webhook/fake",
                HttpMethod.POST,
                new HttpEntity<>(rawBytes, webhookHeaders),
                Map.class
        );

        assertThat(webhookRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhookRes.getBody().get("code")).isEqualTo("UNAUTHORIZED");

        // 3. Status must remain PENDING
        final ResponseEntity<PaymentResponse> getRes = this.restTemplate.exchange(
                baseUrl() + "/api/v1/payments/" + created.paymentId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(customerId, null)),
                PaymentResponse.class
        );
        assertThat(getRes.getBody().status()).isEqualTo(PaymentStatus.PENDING);
    }
}
