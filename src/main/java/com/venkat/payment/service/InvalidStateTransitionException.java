package com.venkat.payment.service;

import com.venkat.payment.domain.PaymentStatus;

/**
 * Thrown when an illegal state transition is attempted on a payment.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final PaymentStatus fromStatus;
    private final PaymentStatus toStatus;

    public InvalidStateTransitionException(final PaymentStatus fromStatus, final PaymentStatus toStatus) {
        super(String.format("Invalid payment state transition from %s to %s", fromStatus, toStatus));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }
}

