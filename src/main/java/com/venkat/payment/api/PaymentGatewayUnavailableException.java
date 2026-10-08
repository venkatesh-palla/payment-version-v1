package com.venkat.payment.api;

/**
 * Thrown when an external payment gateway is unreachable or returns an error (HTTP 503).
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(final String message) {
        super(message);
    }

    public PaymentGatewayUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

