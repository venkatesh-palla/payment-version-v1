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
class PaymentVerificationSchedulerTest {

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
    private PaymentVerificationScheduler verificationScheduler;

    @BeforeEach
    void setUp() {
        this.paymentProperties = new PaymentProperties();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.verificationScheduler = new PaymentVerificationScheduler(
                this.paymentRepository,
                this.gatewayRegistry,
                this.statusUpdateService,
                this.paymentProperties,
                this.fixedClock
        );
    }

    @Test
    @DisplayName("Webhook missed -> Verification scheduler catches pending payment -> Gateway queried -> Payment marked SUCCESS")
    void missedWebhookCaughtByVerificationSchedulerTransitionsToSuccess() {
        final UUID paymentId = UUID.randomUUID();
        final Instant now = this.fixedClock.instant();
        final Instant paidAt = now.minusSeconds(60);

        final Payment pendingPayment = new Payment(
                paymentId, "PAY-POLL-1", "ORD-POLL-1", "CUST-1",
                new BigDecimal("1200.00"), "INR", "fake", "fake_ord_poll_1",
                "upi://fake", PaymentStatus.PENDING, "UPI", null,
                now.plusSeconds(900), null, false, null, 0,
                now.minusSeconds(10), 0L, now.minusSeconds(60), now.minusSeconds(60)
        );

        when(this.paymentRepository.claimPaymentsNeedingVerification(any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(pendingPayment));
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        // Gateway confirms payment actually succeeded at the gateway
        when(this.paymentGateway.verifyPayment("fake_ord_poll_1"))
                .thenReturn(new PaymentVerificationResponse("fake_ord_poll_1", "fake_pay_success_999", PaymentStatus.SUCCESS, new BigDecimal("1200.00"), "INR", "UPI", paidAt));

        this.verificationScheduler.verifyPendingPayments();

        // Verify statusUpdateService was invoked to transition payment to SUCCESS with outbox event
        verify(this.statusUpdateService).transitionStatusWithOutbox(
                eq(pendingPayment),
                eq(PaymentStatus.SUCCESS),
                eq(paidAt),
                eq("fake_pay_success_999"),
                eq(false),
                eq(null),
                eq(false)
        );
    }
}
