package com.venkat.payment.gateway.model;

import java.math.BigDecimal;

/**
 * Common request representation for payment refund.
 */
public record RefundRequest(
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String paymentReference,
        String reason
) {
}

