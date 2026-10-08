package com.venkat.payment.api;

/**
 * Thrown when business payload validation fails (HTTP 400).
 */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(final String message) {
        super(message);
    }
}

