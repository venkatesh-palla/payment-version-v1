package com.venkat.payment.gateway.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Common response representation for payment refund.
 */
public record RefundResponse(
        String refundId,
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String status,
        Instant refundedAt
) {
}

