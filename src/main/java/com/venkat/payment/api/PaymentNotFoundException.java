package com.venkat.payment.api;

import java.util.UUID;

/**
 * Thrown when a requested payment is not found (HTTP 404).
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(final UUID paymentId) {
        super(String.format("Payment not found for id: %s", paymentId));
    }

    public PaymentNotFoundException(final String message) {
        super(message);
    }
}

