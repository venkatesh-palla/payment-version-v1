package com.venkat.payment.gateway.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Response payload returned when creating or retrieving a Razorpay QR code entity.
 */
public record RazorpayQrResponse(
        String id,
        String entity,
        String status,
        @JsonProperty("payment_amount") Long paymentAmount,
        @JsonProperty("image_content") String imageContent,
        @JsonProperty("close_by") Long closeBy,
        @JsonProperty("payments_amount_received") Long paymentsAmountReceived,
        @JsonProperty("created_at") Long createdAt,
        Map<String, String> notes
) {
}

