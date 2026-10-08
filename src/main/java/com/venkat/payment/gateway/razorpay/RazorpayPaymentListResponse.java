package com.venkat.payment.gateway.razorpay;

import java.util.List;

/**
 * List response returned when querying payments for a Razorpay QR code entity.
 */
public record RazorpayPaymentListResponse(
        String entity,
        int count,
        List<RazorpayPaymentItem> items
) {
}

