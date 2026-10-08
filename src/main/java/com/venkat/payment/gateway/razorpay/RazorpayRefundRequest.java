package com.venkat.payment.gateway.razorpay;

import java.util.Map;

/**
 * Request payload for creating a refund against a captured Razorpay payment.
 */
public record RazorpayRefundRequest(
        long amount,
        String receipt,
        Map<String, String> notes
) {
}

