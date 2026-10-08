package com.venkat.payment.api;

/**
 * Parsed webhook payload sent by payment gateways.
 */
public record WebhookPayload(
        String eventId,
        String eventType,
        String gatewayOrderId,
        String gatewayPaymentId
) {
}

