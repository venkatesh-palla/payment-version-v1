package com.venkat.payment.api;

/**
 * Thrown when required Idempotency-Key header is missing or invalid (HTTP 400).
 */
public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException(final String message) {
        super(message);
    }
}

