package com.venkat.payment.api;

/**
 * Thrown when an idempotency key is reused with a different request payload (HTTP 422).
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(final String message) {
        super(message);
    }
}

