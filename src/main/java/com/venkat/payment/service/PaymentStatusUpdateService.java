package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.repository.PaymentOutboxRepository;
import com.venkat.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single shared service orchestrating payment status changes and atomic transactional outbox event insertion.
 * All status changes (Webhook, Expiry, Verification) must route through this service.
 */
@Service
public class PaymentStatusUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusUpdateService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxRepository outboxRepository;
    private final PaymentStateMachine stateMachine;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentStatusUpdateService(final PaymentRepository paymentRepository,
                                      final PaymentOutboxRepository outboxRepository,
                                      final PaymentStateMachine stateMachine,
                                      final ObjectMapper objectMapper,
                                      final Clock clock) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Executes atomic status transition and transactional outbox event generation in ONE DB transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transitionStatusWithOutbox(final Payment payment,
                                              final PaymentStatus targetStatus,
                                              final Instant paidAt,
                                              final String gatewayPaymentId,
                                              final boolean requiresManualReview,
                                              final String reviewReason,
                                              final boolean lateSuccess) {
        final PaymentStatus currentStatus = payment.getStatus();
        if (currentStatus == targetStatus) {
            log.info("Idempotent transition no-op: payment [{}] already in status {}",
                    payment.getPaymentReference(), targetStatus);
            return false;
        }

        // Validate state machine eligibility
        if (!lateSuccess && !this.stateMachine.canTransition(currentStatus, targetStatus)) {
            log.info("Ignoring illegal/out-of-order transition from {} to {} for payment [{}]",
                    currentStatus, targetStatus, payment.getPaymentReference());
            return false;
        }

        final Set<PaymentStatus> allowedStates = new HashSet<>(this.stateMachine.allowedFromStates(targetStatus));
        allowedStates.add(currentStatus);
        if (lateSuccess && targetStatus == PaymentStatus.SUCCESS) {
            allowedStates.add(PaymentStatus.EXPIRED);
        }

        final Set<String> allowedStateNames = allowedStates.stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

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
            log.info("Optimistic locking / guard mismatch on payment [{}]: 0 rows updated",
                    payment.getPaymentReference());
            return false;
        }

        // Map status to domain event
        final String eventType = switch (targetStatus) {
            case SUCCESS -> "PAYMENT_SUCCESS";
            case FAILED -> "PAYMENT_FAILED";
            case EXPIRED -> "PAYMENT_EXPIRED";
            case REFUNDED -> "PAYMENT_REFUNDED";
            default -> null;
        };

        if (eventType != null) {
            final String eventId = payment.getId() + ":" + eventType;
            final Instant now = this.clock.instant();
            final OutboxEventPayload eventPayload = new OutboxEventPayload(
                    eventId,
                    eventType,
                    1,
                    now,
                    payment.getId(),
                    payment.getPaymentReference(),
                    payment.getOrderId(),
                    payment.getCustomerId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    gatewayPaymentId != null ? gatewayPaymentId : payment.getGatewayPaymentId(),
                    paidAt != null ? paidAt : payment.getPaidAt(),
                    lateSuccess,
                    targetStatus == PaymentStatus.FAILED ? reviewReason : null,
                    targetStatus == PaymentStatus.EXPIRED ? now : null
            );

            try {
                final String payloadJson = this.objectMapper.writeValueAsString(eventPayload);
                this.outboxRepository.insertOutboxEvent(
                        payment.getId(),
                        eventId,
                        eventType,
                        payloadJson
                );
                log.info("Inserted outbox event [{}] for payment [{}] into payment_outbox",
                        eventId, payment.getPaymentReference());
            } catch (final Exception ex) {
                log.error("Failed to serialize outbox event payload", ex);
                throw new IllegalStateException("Failed to serialize outbox event", ex);
            }
        }

        log.info("Payment [{}] transitioned from {} to {}", payment.getPaymentReference(), currentStatus, targetStatus);
        return true;
    }
}

