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
        Instant expiredAt
) {
}

