package com.venkat.payment.api;

import com.venkat.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned upon successful payment refund processing.
 */
public record RefundPaymentResponse(
        UUID paymentId,
        PaymentStatus status,
        String refundId,
        BigDecimal refundedAmount,
        String currency,
        Instant refundedAt
) {
}

