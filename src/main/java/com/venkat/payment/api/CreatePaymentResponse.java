package com.venkat.payment.api;

import com.venkat.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned upon successful payment creation (HTTP 201).
 */
public record CreatePaymentResponse(
        UUID paymentId,
        String paymentReference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String qrCode,
        Instant expiresAt
) {
}

