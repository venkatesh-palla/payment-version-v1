package com.venkat.payment.service;

import com.venkat.payment.domain.PaymentStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for payment lifecycle state transitions.
 */
@Component
public class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> FORWARD_TRANSITIONS = new EnumMap<>(PaymentStatus.class);
    private static final Map<PaymentStatus, Set<PaymentStatus>> REVERSE_TRANSITIONS = new EnumMap<>(PaymentStatus.class);

    static {
        FORWARD_TRANSITIONS.put(PaymentStatus.CREATED, EnumSet.of(
                PaymentStatus.QR_GENERATED,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELLED
        ));
        FORWARD_TRANSITIONS.put(PaymentStatus.QR_GENERATED, EnumSet.of(
                PaymentStatus.PENDING,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELLED
        ));
        FORWARD_TRANSITIONS.put(PaymentStatus.PENDING, EnumSet.of(
                PaymentStatus.SUCCESS,
                PaymentStatus.FAILED,
                PaymentStatus.EXPIRED,
                PaymentStatus.CANCELLED
        ));
        FORWARD_TRANSITIONS.put(PaymentStatus.SUCCESS, EnumSet.of(
                PaymentStatus.REFUNDED
        ));
        FORWARD_TRANSITIONS.put(PaymentStatus.EXPIRED, EnumSet.of(
                PaymentStatus.SUCCESS
        ));
        FORWARD_TRANSITIONS.put(PaymentStatus.FAILED, Collections.emptySet());
        FORWARD_TRANSITIONS.put(PaymentStatus.CANCELLED, Collections.emptySet());
        FORWARD_TRANSITIONS.put(PaymentStatus.REFUNDED, Collections.emptySet());

        // Build allowedFromStates reverse index
        for (final PaymentStatus status : PaymentStatus.values()) {
            REVERSE_TRANSITIONS.put(status, EnumSet.noneOf(PaymentStatus.class));
        }
        for (final Map.Entry<PaymentStatus, Set<PaymentStatus>> entry : FORWARD_TRANSITIONS.entrySet()) {
            final PaymentStatus from = entry.getKey();
            for (final PaymentStatus to : entry.getValue()) {
                REVERSE_TRANSITIONS.get(to).add(from);
            }
        }
    }

    /**
     * Determines whether a transition from one status to another is permitted.
     * Note: Same state to same state is permitted as a harmless no-op.
     *
     * @param from current status
     * @param to   target status
     * @return true if valid or identical, false otherwise
     */
    public boolean canTransition(final PaymentStatus from, final PaymentStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        final Set<PaymentStatus> allowed = FORWARD_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates transition from one status to another.
     * Throws InvalidStateTransitionException if transition is invalid.
     *
     * @param from current status
     * @param to   target status
     * @throws InvalidStateTransitionException if illegal
     */
    public void validate(final PaymentStatus from, final PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }

    /**
     * Returns the set of predecessor states that are permitted to transition to the given state.
     *
     * @param to target state
     * @return unmodifiable set of allowed from states
     */
    public Set<PaymentStatus> allowedFromStates(final PaymentStatus to) {
        if (to == null) {
            return Collections.emptySet();
        }
        final Set<PaymentStatus> fromStates = REVERSE_TRANSITIONS.get(to);
        return fromStates == null ? Collections.emptySet() : Collections.unmodifiableSet(fromStates);
    }
}

