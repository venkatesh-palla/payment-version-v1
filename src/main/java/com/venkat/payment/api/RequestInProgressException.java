package com.venkat.payment.api;

/**
 * Thrown when an identical request with the same idempotency key is currently processing (HTTP 409).
 */
public class RequestInProgressException extends RuntimeException {

    private final int retryAfterSeconds;

    public RequestInProgressException(final String message, final int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

