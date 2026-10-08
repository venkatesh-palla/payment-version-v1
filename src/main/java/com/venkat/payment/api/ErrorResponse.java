package com.venkat.payment.api;

import java.time.Instant;

/**
 * Standard error response envelope across all endpoints.
 */
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp
) {
}

