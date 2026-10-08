package com.venkat.payment.scheduler;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.repository.PaymentRepository;
import com.venkat.payment.service.PaymentStatusUpdateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentExpirySchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentStatusUpdateService statusUpdateService;

    private PaymentProperties paymentProperties;
    private Clock fixedClock;
    private PaymentExpiryScheduler expiryScheduler;

    @BeforeEach
    void setUp() {
        this.paymentProperties = new PaymentProperties();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.expiryScheduler = new PaymentExpiryScheduler(
                this.paymentRepository,
                this.gatewayRegistry,
                this.statusUpdateService,
                this.paymentProperties,
                this.fixedClock
        );
    }

    @Test
    @DisplayName("Payment expires -> Expiry scheduler marks it EXPIRED -> Outbox records PAYMENT_EXPIRED")
    void expiredPendingPaymentTransitionsToExpiredAndEmitsOutbox() {
        final UUID paymentId = UUID.randomUUID();
        final Instant expiredTime = this.fixedClock.instant().minusSeconds(600);

        final Payment pendingPayment = new Payment(
                paymentId, "PAY-EXP-1", "ORD-EXP-1", "CUST-1",
                new BigDecimal("250.00"), "INR", "fake", "fake_ord_exp_1",
                "upi://fake", PaymentStatus.PENDING, "UPI", null,
                expiredTime, null, false, null, 0, null, 0L,
                expiredTime.minusSeconds(900), expiredTime
        );

        when(this.paymentRepository.claimExpiredPendingPayments(any(Instant.class), anyInt()))
                .thenReturn(List.of(pendingPayment));
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        // Gateway confirms payment was never completed
        when(this.paymentGateway.verifyPayment("fake_ord_exp_1"))
                .thenReturn(new PaymentVerificationResponse("fake_ord_exp_1", null, PaymentStatus.PENDING, new BigDecimal("250.00"), "INR", "UPI", null));

        this.expiryScheduler.processExpiredPayments();

        // Verify statusUpdateService was called to transition to EXPIRED with outbox event
        verify(this.statusUpdateService).transitionStatusWithOutbox(
                eq(pendingPayment),
                eq(PaymentStatus.EXPIRED),
                eq(null),
                eq(null),
                eq(false),
                eq(null),
                eq(false)
        );
    }
}
