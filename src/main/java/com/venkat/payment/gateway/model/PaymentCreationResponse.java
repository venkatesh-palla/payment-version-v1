package com.venkat.payment.gateway.model;

import java.time.Instant;

/**
 * Gateway response record from payment / QR creation.
 */
public record PaymentCreationResponse(
        String gatewayOrderId,
        String qrData,
        Instant expiresAt
) {
}

