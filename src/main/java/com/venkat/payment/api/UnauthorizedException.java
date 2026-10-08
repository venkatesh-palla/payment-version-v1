package com.venkat.payment.api;

/**
 * Thrown when an authentication or signature check fails (HTTP 401).
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(final String message) {
        super(message);
    }
}

