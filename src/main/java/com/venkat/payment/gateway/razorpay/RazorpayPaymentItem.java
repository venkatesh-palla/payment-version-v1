package com.venkat.payment.gateway.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Individual payment transaction item associated with a Razorpay QR code.
 */
public record RazorpayPaymentItem(
        String id,
        String entity,
        long amount,
        String currency,
        String status,
        String method,
        @JsonProperty("created_at") Long createdAt
) {
}

