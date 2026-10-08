package com.venkat.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Standard versioned schema for Payment domain events dispatched via Outbox.
 * Contains minimal PII and zero secrets.
 */
public record OutboxEventPayload(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID paymentId,
        String paymentReference,
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        String gatewayPaymentId,
        Instant paidAt,
        boolean lateSuccess,
        String failureReason,
        Instant expiredAt,
        String refundId,
        BigDecimal refundedAmount
) {
    public OutboxEventPayload(
            final String eventId,
            final String eventType,
            final int schemaVersion,
            final Instant occurredAt,
            final UUID paymentId,
            final String paymentReference,
            final String orderId,
            final String customerId,
            final BigDecimal amount,
            final String currency,
            final String gatewayPaymentId,
            final Instant paidAt,
            final boolean lateSuccess,
            final String failureReason,
            final Instant expiredAt) {
        this(eventId, eventType, schemaVersion, occurredAt, paymentId, paymentReference,
                orderId, customerId, amount, currency, gatewayPaymentId, paidAt,
                lateSuccess, failureReason, expiredAt, null, null);
    }
}
