package com.venkat.payment.api;

import com.venkat.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned when querying payment details (HTTP 200).
 */
public record PaymentResponse(
        UUID paymentId,
        String paymentReference,
        String orderId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        Instant paidAt,
        Instant expiresAt
) {
}

