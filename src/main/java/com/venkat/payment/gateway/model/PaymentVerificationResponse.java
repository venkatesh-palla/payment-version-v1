package com.venkat.payment.gateway.model;

import com.venkat.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Gateway response record from payment verification query.
 */
public record PaymentVerificationResponse(
        String gatewayOrderId,
        String gatewayPaymentId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        Instant paidAt
) {
}

