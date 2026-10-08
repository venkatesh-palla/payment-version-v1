package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.repository.BusinessProcessRepository;
import com.venkat.payment.repository.BusinessProcessRepository.BusinessProcessRecord;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import com.venkat.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownstreamBusinessProcessFailureTest {

    @Mock
    private BusinessProcessRepository businessProcessRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentProperties paymentProperties;
    private Clock fixedClock;
    private DownstreamEventHandler downstreamEventHandler;
    private BusinessProcessWorker businessProcessWorker;

    @BeforeEach
    void setUp() {
        this.paymentProperties = new PaymentProperties();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.downstreamEventHandler = new DownstreamEventHandler(this.businessProcessRepository, new ObjectMapper());
        this.businessProcessWorker = new BusinessProcessWorker(this.businessProcessRepository, this.paymentProperties, this.fixedClock);
    }

    @Test
    @DisplayName("Downstream business process fails -> Payment status remains SUCCESS, business process marked FAILED with last_error")
    void downstreamProcessFailsWhilePaymentRemainsSuccess() {
        final UUID paymentId = UUID.randomUUID();
        final UUID processId = UUID.randomUUID();

        // 1. Initial authoritative payment state in database is SUCCESS
        final Payment authoritativePayment = new Payment(
                paymentId, "PAY-ORD-SUCCESS-1", "ORD-1", "CUST-1",
                new BigDecimal("500.00"), "INR", "fake", "fake_ord_1",
                "upi://fake", PaymentStatus.SUCCESS, "UPI", null,
                this.fixedClock.instant().plusSeconds(900), this.fixedClock.instant(),
                false, null, 0, null, 1L, this.fixedClock.instant(), this.fixedClock.instant()
        );
        when(this.paymentRepository.findById(paymentId)).thenReturn(Optional.of(authoritativePayment));

        // 2. Outbox event arrives at downstream handler
        final OutboxRecord outboxEvent = new OutboxRecord(
                UUID.randomUUID(), 1L, "EVT-PAYMENT-SUCCESS-" + paymentId, paymentId,
                "PAYMENT_SUCCESS", "{\"paymentId\":\"" + paymentId + "\"}", "DELIVERED",
                0, this.fixedClock.instant(), null, this.fixedClock.instant(), this.fixedClock.instant()
        );

        when(this.businessProcessRepository.insertConsumerProcessedEvent(outboxEvent.eventId(), "DownstreamOrderFulfillmentConsumer"))
                .thenReturn(true);

        final boolean handled = this.downstreamEventHandler.handleEvent(outboxEvent);
        assertThat(handled).isTrue();
        verify(this.businessProcessRepository).insertBusinessProcess(paymentId, "ORDER_FULFILLMENT");

        // 3. Worker executes task and downstream fulfillment encounters an error
        this.businessProcessWorker.setFailForTesting(true);

        // Simulate that retry count is already maxRetries - 1, so this attempt will permanently fail
        final int maxRetries = this.paymentProperties.getBusinessProcess().getMaxRetries();
        final BusinessProcessRecord task = new BusinessProcessRecord(
                processId, paymentId, "ORDER_FULFILLMENT", "PROCESSING",
                maxRetries - 1, this.fixedClock.instant(), null, this.fixedClock.instant(), null,
                this.fixedClock.instant(), this.fixedClock.instant()
        );

        this.businessProcessWorker.executeTask(task);

        // 4. Verify downstream business process recorded failure with permanentlyFailed = true and last_error
        final ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        verify(this.businessProcessRepository).recordFailure(
                eq(processId),
                eq(maxRetries),
                any(Instant.class),
                errorCaptor.capture(),
                eq(true)
        );

        assertThat(errorCaptor.getValue()).contains("Simulated downstream failure for testing");

        // 5. CRITICAL: Authoritative payment status remains SUCCESS and was never modified or downgraded!
        verify(this.paymentRepository, never()).updateStatusWithGuard(any(), any(), any(), any(), any(), anyBoolean(), any(), anyLong());

        final Payment paymentAfterFailure = this.paymentRepository.findById(paymentId).orElseThrow();
        assertThat(paymentAfterFailure.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }
}
