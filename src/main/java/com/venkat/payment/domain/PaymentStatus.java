package com.venkat.payment.domain;

/**
 * Lifecycle states for a payment.
 */
public enum PaymentStatus {
    CREATED,
    QR_GENERATED,
    PENDING,
    SUCCESS,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED
}

