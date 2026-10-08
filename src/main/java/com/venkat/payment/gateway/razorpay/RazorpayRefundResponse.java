package com.venkat.payment.gateway.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload returned by Razorpay after initiating a refund.
 */
public record RazorpayRefundResponse(
        String id,
        String entity,
        long amount,
        String currency,
        @JsonProperty("payment_id") String paymentId,
        String status,
        String receipt,
        @JsonProperty("created_at") Long createdAt
) {
}

