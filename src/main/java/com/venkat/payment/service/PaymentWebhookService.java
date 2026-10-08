package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.InvalidInputException;
import com.venkat.payment.api.PaymentGatewayUnavailableException;
import com.venkat.payment.api.UnauthorizedException;
import com.venkat.payment.api.WebhookPayload;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.repository.PaymentEventRepository;
import com.venkat.payment.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service processing incoming payment webhooks with cryptographic verification,
 * deduplication, out-of-band gateway verification, and status-guarded state updates.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public PaymentWebhookService(final PaymentRepository paymentRepository,
                                 final PaymentEventRepository paymentEventRepository,
                                 final PaymentGatewayRegistry gatewayRegistry,
                                 final PaymentStateMachine stateMachine,
                                 final ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the strict 11-step webhook processing pipeline.
     *
     * @param gatewayName provider gateway name
     * @param rawBody     unparsed byte payload
     * @param signature   header signature
     * @return processing outcome summary
     */
    public String processWebhook(final String gatewayName, final byte[] rawBody, final String signature) {
        final PaymentGateway gateway = this.gatewayRegistry.getGateway(gatewayName);

        // 1 & 2. Verify signature directly against raw bytes
        if (!gateway.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Invalid webhook signature received for gateway [{}]", gatewayName);
            throw new UnauthorizedException("Invalid webhook signature");
        }

        // 3. Parse JSON after signature verification
        final WebhookPayload payload;
        try {
            payload = this.objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (final Exception e) {
            log.warn("Malformed webhook JSON payload: {}", e.getMessage());
            throw new InvalidInputException("Malformed webhook JSON payload");
        }

        if (payload == null || payload.eventId() == null || payload.gatewayOrderId() == null) {
            throw new InvalidInputException("Webhook payload missing eventId or gatewayOrderId");
        }

        final String rawPayloadJson = new String(rawBody, StandardCharsets.UTF_8);

        // 4. Deduplication check
        if (this.paymentEventRepository.isEventProcessed(payload.eventId())) {
            log.info("Webhook event [{}] is already processed. Duplicate ignored.", payload.eventId());
            return "duplicate";
        }

        // 5. Find payment by gateway_order_id
        final Optional<Payment> paymentOpt = this.paymentRepository.findByGatewayOrderId(payload.gatewayOrderId());
        if (paymentOpt.isEmpty()) {
            log.warn("Webhook received for unknown gatewayOrderId [{}], eventId [{}]",
                    payload.gatewayOrderId(), payload.eventId());
            this.paymentEventRepository.insertEventIfNotExists(
                    UUID.randomUUID(),
                    null,
                    payload.eventId(),
                    payload.eventType(),
                    "WEBHOOK",
                    rawPayloadJson
            );
            this.paymentEventRepository.markEventProcessed(payload.eventId());
            return "unknown_payment";
        }

        final Payment payment = paymentOpt.get();

        // 6. Verify status with gateway (NO open DB transaction)
        final PaymentVerificationResponse verified;
        try {
            verified = gateway.verifyPayment(payment.getGatewayOrderId());
        } catch (final Exception ex) {
            log.error("Gateway verification call failed for order [{}]", payment.getGatewayOrderId(), ex);
            throw new PaymentGatewayUnavailableException("Gateway unavailable for payment verification", ex);
        }

        // 7. Validate verified data against our payment record
        final boolean amountMatches = verified.amount() != null
                && verified.amount().compareTo(payment.getAmount()) == 0;
        final boolean currencyMatches = verified.currency() != null
                && verified.currency().equalsIgnoreCase(payment.getCurrency());
        final boolean orderIdMatches = verified.gatewayOrderId() != null
                && verified.gatewayOrderId().equals(payment.getGatewayOrderId());

        if (!amountMatches || !currencyMatches || !orderIdMatches) {
            final String mismatchReason = !amountMatches ? "AMOUNT_MISMATCH"
                    : (!currencyMatches ? "CURRENCY_MISMATCH" : "GATEWAY_ORDER_MISMATCH");

            log.warn("Verification mismatch for payment [{}]: reason={}",
                    payment.getPaymentReference(), mismatchReason);

            handleVerificationMismatch(
                    payment.getId(),
                    payload.eventId(),
                    payload.eventType(),
                    rawPayloadJson,
                    mismatchReason,
                    payment.getVersion()
            );
            return "mismatch_recorded";
        }

        // 8. Decide new status from VERIFIED gateway response (never from webhook body)
        final PaymentStatus verifiedStatus = verified.status() != null ? verified.status() : PaymentStatus.PENDING;
        final PaymentStatus currentStatus = payment.getStatus();
        PaymentStatus targetStatus = verifiedStatus;
        boolean requiresManualReview = false;
        String reviewReason = null;

        if (verifiedStatus == PaymentStatus.SUCCESS) {
            if (currentStatus == PaymentStatus.EXPIRED) {
                // Late payment after expiry allowed but flagged for review
                requiresManualReview = true;
                reviewReason = "LATE_PAYMENT_AFTER_EXPIRY";
                targetStatus = PaymentStatus.SUCCESS;
            } else if (!this.stateMachine.canTransition(currentStatus, PaymentStatus.SUCCESS)) {
                log.info("Ignoring out-of-order transition from {} to SUCCESS for payment [{}]",
                        currentStatus, payment.getPaymentReference());
                return "ignored_out_of_order";
            }
        } else {
            if (!this.stateMachine.canTransition(currentStatus, targetStatus)) {
                log.info("Ignoring out-of-order transition from {} to {} for payment [{}]",
                        currentStatus, targetStatus, payment.getPaymentReference());
                return "ignored_out_of_order";
            }
        }

        // 9. Execute atomic status update and event deduplication in ONE DB transaction
        final boolean updated = executeAtomicStatusUpdate(
                payment,
                targetStatus,
                verified.paidAt(),
                verified.gatewayPaymentId(),
                requiresManualReview,
                reviewReason,
                payload.eventId(),
                payload.eventType(),
                rawPayloadJson
        );

        if (updated) {
            // 10. Trigger post-update hook
            onPaymentStatusChanged(payment, currentStatus, targetStatus);
        }

        // 11. Return 200 OK
        return "processed";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeAtomicStatusUpdate(final Payment payment,
                                             final PaymentStatus targetStatus,
                                             final Instant paidAt,
                                             final String gatewayPaymentId,
                                             final boolean requiresManualReview,
                                             final String reviewReason,
                                             final String eventId,
                                             final String eventType,
                                             final String rawPayload) {
        // Atomic insert event: ON CONFLICT DO NOTHING
        final boolean inserted = this.paymentEventRepository.insertEventIfNotExists(
                UUID.randomUUID(),
                payment.getId(),
                eventId,
                eventType,
                "WEBHOOK",
                rawPayload
        );

        if (!inserted) {
            log.info("Concurrent webhook event [{}] race detected. Skipping update.", eventId);
            return false;
        }

        // Status-guarded update with version check
        final Set<PaymentStatus> allowedStates = this.stateMachine.allowedFromStates(targetStatus);
        final Set<String> allowedStateNames = allowedStates.stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        // Also allow same-state idempotent update
        allowedStateNames.add(targetStatus.name());
        if (targetStatus == PaymentStatus.SUCCESS && payment.getStatus() == PaymentStatus.EXPIRED) {
            allowedStateNames.add(PaymentStatus.EXPIRED.name());
        }

        final int rows = this.paymentRepository.updateStatusWithGuard(
                payment.getId(),
                targetStatus,
                allowedStateNames,
                paidAt,
                gatewayPaymentId,
                requiresManualReview,
                reviewReason,
                payment.getVersion()
        );

        if (rows == 0) {
            log.info("Status-guarded update for payment [{}] produced 0 rows (out of order or version race)",
                    payment.getPaymentReference());
        }

        this.paymentEventRepository.markEventProcessed(eventId);
        return rows > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleVerificationMismatch(final UUID paymentId,
                                           final String eventId,
                                           final String eventType,
                                           final String rawPayload,
                                           final String mismatchReason,
                                           final long expectedVersion) {
        this.paymentEventRepository.insertEventIfNotExists(
                UUID.randomUUID(),
                paymentId,
                eventId,
                eventType,
                "WEBHOOK",
                rawPayload
        );
        this.paymentRepository.updateStatusWithGuard(
                paymentId,
                PaymentStatus.PENDING,
                Set.of(PaymentStatus.PENDING.name(), PaymentStatus.QR_GENERATED.name()),
                null,
                null,
                true,
                mismatchReason,
                expectedVersion
        );
        this.paymentEventRepository.markEventProcessed(eventId);
    }

    /**
     * Level 1 status changed hook. In Level 2 this will insert an event into the transactional outbox.
     */
    public void onPaymentStatusChanged(final Payment payment, final PaymentStatus oldStatus, final PaymentStatus newStatus) {
        log.info("Payment [{}] status changed from {} to {}", payment.getPaymentReference(), oldStatus, newStatus);
    }
}

