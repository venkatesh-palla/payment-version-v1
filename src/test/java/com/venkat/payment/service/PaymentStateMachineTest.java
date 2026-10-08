package com.venkat.payment.service;

import com.venkat.payment.domain.PaymentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private PaymentStateMachine stateMachine;

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED_LEGAL_TRANSITIONS = Map.of(
            PaymentStatus.CREATED, Set.of(PaymentStatus.QR_GENERATED, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.QR_GENERATED, Set.of(PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.PENDING, Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED),
            PaymentStatus.SUCCESS, Set.of(PaymentStatus.REFUNDED),
            PaymentStatus.EXPIRED, Set.of(PaymentStatus.SUCCESS),
            PaymentStatus.FAILED, Set.of(),
            PaymentStatus.CANCELLED, Set.of(),
            PaymentStatus.REFUNDED, Set.of()
    );

    @BeforeEach
    void setUp() {
        this.stateMachine = new PaymentStateMachine();
    }

    static Stream<Arguments> fullTransitionMatrix() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final PaymentStatus from : PaymentStatus.values()) {
            for (final PaymentStatus to : PaymentStatus.values()) {
                final boolean isExpectedLegal = (from == to) || EXPECTED_LEGAL_TRANSITIONS.get(from).contains(to);
                arguments.add(Arguments.of(from, to, isExpectedLegal));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "Transition {0} -> {1} (expected allowed: {2})")
    @MethodSource("fullTransitionMatrix")
    @DisplayName("Verify exhaustive 8x8 status transition matrix")
    void verifyTransitionMatrix(final PaymentStatus from, final PaymentStatus to, final boolean shouldBeAllowed) {
        final boolean can = this.stateMachine.canTransition(from, to);
        assertThat(can).isEqualTo(shouldBeAllowed);

        if (shouldBeAllowed) {
            assertThatCode(() -> this.stateMachine.validate(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> this.stateMachine.validate(from, to))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining(String.format("Invalid payment state transition from %s to %s", from, to));
        }
    }

    @ParameterizedTest(name = "Verify allowedFromStates for target: {0}")
    @MethodSource("allStatuses")
    @DisplayName("Verify reverse lookup of allowedFromStates")
    void verifyReverseAllowedFromStates(final PaymentStatus target) {
        final Set<PaymentStatus> fromStates = this.stateMachine.allowedFromStates(target);
        for (final PaymentStatus from : PaymentStatus.values()) {
            if (from == target) {
                continue; // self transition handled separately
            }
            if (EXPECTED_LEGAL_TRANSITIONS.get(from).contains(target)) {
                assertThat(fromStates).contains(from);
            } else {
                assertThat(fromStates).doesNotContain(from);
            }
        }
    }

    static Stream<PaymentStatus> allStatuses() {
        return Stream.of(PaymentStatus.values());
    }
}

