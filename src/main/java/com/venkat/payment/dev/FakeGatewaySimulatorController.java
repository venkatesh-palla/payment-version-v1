package com.venkat.payment.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.PaymentNotFoundException;
import com.venkat.payment.api.WebhookPayload;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.fake.FakeGateway;
import com.venkat.payment.repository.PaymentRepository;
import com.venkat.payment.service.PaymentWebhookService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulator controller available only in dev/test profiles to trigger signed webhooks.
 */
@RestController
@RequestMapping("/dev/fake-gateway")
@Profile("!prod & !production")
@ConditionalOnProperty(prefix = "payment.dev", name = "enabled", havingValue = "true")
public class FakeGatewaySimulatorController {

    private static final Logger log = LoggerFactory.getLogger(FakeGatewaySimulatorController.class);

    private final PaymentRepository paymentRepository;
    private final FakeGateway fakeGateway;
    private final PaymentWebhookService webhookService;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;

    public FakeGatewaySimulatorController(final PaymentRepository paymentRepository,
                                          final FakeGateway fakeGateway,
                                          final PaymentWebhookService webhookService,
                                          final PaymentProperties paymentProperties,
                                          final ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.fakeGateway = fakeGateway;
        this.webhookService = webhookService;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{paymentReference}/simulate")
    public ResponseEntity<Map<String, Object>> simulate(
            @PathVariable("paymentReference") final String paymentReference,
            @RequestParam(name = "result", defaultValue = "success") final String result) {
        log.info("Simulating payment event for ref={} with result={}", paymentReference, result);

        final Payment payment = this.paymentRepository.findByPaymentReference(paymentReference)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for ref: " + paymentReference));

        final String gatewayOrderId = payment.getGatewayOrderId();
        final String eventId = "duplicate".equalsIgnoreCase(result)
                ? "evt_duplicate_sample_" + payment.getId()
                : "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        final String eventType;
        BigDecimal overrideAmount = null;

        switch (result.toLowerCase()) {
            case "failed" -> {
                this.fakeGateway.setOrderState(gatewayOrderId, PaymentStatus.FAILED, null, null, null, null);
                eventType = "payment.failed";
            }
            case "pending" -> {
                this.fakeGateway.setOrderState(gatewayOrderId, PaymentStatus.PENDING, null, null, null, null);
                eventType = "payment.pending";
            }
            case "amount_mismatch" -> {
                // Set fake gateway order amount mismatched by +10.00
                overrideAmount = payment.getAmount().add(new BigDecimal("10.00"));
                this.fakeGateway.setOrderState(gatewayOrderId, PaymentStatus.SUCCESS, null, Instant.now(), overrideAmount, null);
                eventType = "payment.success";
            }
            case "duplicate", "success" -> {
                this.fakeGateway.setOrderState(gatewayOrderId, PaymentStatus.SUCCESS, null, Instant.now(), null, null);
                eventType = "payment.success";
            }
            default -> throw new IllegalArgumentException("Unsupported simulation result: " + result);
        }

        final FakeGateway.FakeOrderState updatedState = this.fakeGateway.getOrderState(gatewayOrderId);

        // Build authentic signed webhook payload
        final WebhookPayload payload = new WebhookPayload(
                eventId,
                eventType,
                gatewayOrderId,
                updatedState.gatewayPaymentId()
        );

        try {
            final byte[] rawBytes = this.objectMapper.writeValueAsBytes(payload);
            final String signature = FakeGateway.calculateHmacSha256(
                    rawBytes,
                    this.paymentProperties.getFakeGatewayWebhookSecret()
            );

            // Execute the REAL webhook code path
            final String webhookOutcome = this.webhookService.processWebhook("fake", rawBytes, signature);

            return ResponseEntity.ok(Map.of(
                    "status", "SIMULATED",
                    "simulationResult", result,
                    "paymentReference", paymentReference,
                    "gatewayOrderId", gatewayOrderId,
                    "eventId", eventId,
                    "webhookOutcome", webhookOutcome
            ));
        } catch (final Exception e) {
            log.error("Simulation failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }
}

