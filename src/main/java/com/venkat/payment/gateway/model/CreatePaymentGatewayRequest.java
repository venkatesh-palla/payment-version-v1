package com.venkat.payment.gateway.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Gateway request record for creating a new payment / QR code.
 */
public record CreatePaymentGatewayRequest(
        String paymentReference,
        BigDecimal amount,
        String currency,
        String orderId,
        Instant expiresAt
) {
}

