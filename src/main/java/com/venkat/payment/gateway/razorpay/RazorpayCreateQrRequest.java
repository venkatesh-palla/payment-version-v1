package com.venkat.payment.gateway.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Request payload for creating a single-use dynamic UPI QR code in Razorpay.
 */
public record RazorpayCreateQrRequest(
        String type,
        String name,
        String usage,
        @JsonProperty("fixed_amount") boolean fixedAmount,
        @JsonProperty("payment_amount") long paymentAmount,
        String description,
        @JsonProperty("close_by") long closeBy,
        Map<String, String> notes
) {
}

