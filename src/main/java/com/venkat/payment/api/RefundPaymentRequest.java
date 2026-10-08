package com.venkat.payment.api;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Request body for initiating a refund against a payment.
 * Currently supports full refund. Partial refunds will be supported in a future version.
 */
public record RefundPaymentRequest(
        @DecimalMin(value = "0.01", message = "Refund amount must be strictly positive")
        BigDecimal amount,
        String reason
) {
}

