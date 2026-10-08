package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.PaymentGatewayUnavailableException;
import com.venkat.payment.api.UnauthorizedException;
import com.venkat.payment.api.WebhookPayload;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.repository.PaymentEventRepository;
import com.venkat.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentStateMachine stateMachine;
    private ObjectMapper objectMapper;
    private PaymentWebhookService webhookService;

    private static final String GATEWAY_NAME = "fake";
    private static final String VALID_SIGNATURE = "valid_hex_signature";

    @BeforeEach
    void setUp() {
        this.stateMachine = new PaymentStateMachine();
        this.objectMapper = new ObjectMapper();
        this.webhookService = new PaymentWebhookService(
                this.paymentRepository,
                this.paymentEventRepository,
                this.gatewayRegistry,
                this.stateMachine,
                this.objectMapper
        );
        when(this.gatewayRegistry.getGateway(GATEWAY_NAME)).thenReturn(this.paymentGateway);
    }

    private Payment createTestPayment(final PaymentStatus status) {
        return new Payment(
                UUID.randomUUID(),
                "PAY-TEST12345678",
                "ORD-999",
                "CUST-1",
                new BigDecimal("500.00"),
                "INR",
                GATEWAY_NAME,
                "fake_ord_1",
                null,
                status,
                "UPI",
                "upi://pay...",
                Instant.now().plusSeconds(600),
                null,
                false,
                null,
                0,
                null,
                0L,
                Instant.now(),
                Instant.now()
        );
    }

    private byte[] createPayloadBytes(final String eventId, final String gatewayOrderId) throws Exception {
        final WebhookPayload payload = new WebhookPayload(eventId, "payment.success", gatewayOrderId, "fake_pay_1");
        return this.objectMapper.writeValueAsBytes(payload);
    }

    @Test
    @DisplayName("Invalid signature must immediately throw UnauthorizedException (401)")
    void invalidSignatureThrows401() {
        final byte[] rawBody = "{\"eventId\":\"evt_1\"}".getBytes();
        when(this.paymentGateway.verifyWebhookSignature(rawBody, "bad-sig")).thenReturn(false);

        assertThatThrownBy(() -> this.webhookService.processWebhook(GATEWAY_NAME, rawBody, "bad-sig"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid webhook signature");

        verify(this.paymentRepository, never()).findByGatewayOrderId(any());
    }

    @Test
    @DisplayName("Duplicate processed event must return 'duplicate' without changes")
    void duplicateEventIgnored() throws Exception {
        final byte[] rawBody = createPayloadBytes("evt_dup", "fake_ord_1");
        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_dup")).thenReturn(true);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("duplicate");
        verify(this.paymentRepository, never()).findByGatewayOrderId(any());
    }

    @Test
    @DisplayName("Webhook for unknown gatewayOrderId logs warning, records audit event, and returns 200")
    void unknownPaymentHandledGracefully() throws Exception {
        final byte[] rawBody = createPayloadBytes("evt_unknown", "fake_ord_unknown");
        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_unknown")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId("fake_ord_unknown")).thenReturn(Optional.empty());

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("unknown_payment");
        verify(this.paymentEventRepository).insertEventIfNotExists(any(), eq(null), eq("evt_unknown"), any(), eq("WEBHOOK"), any());
        verify(this.paymentEventRepository).markEventProcessed("evt_unknown");
    }

    @Test
    @DisplayName("Verified SUCCESS payment updates status to SUCCESS atomically")
    void successWebhookUpdatesStatus() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.PENDING);
        final byte[] rawBody = createPayloadBytes("evt_success", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_success")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_success",
                PaymentStatus.SUCCESS,
                new BigDecimal("500.00"),
                "INR",
                "UPI",
                Instant.now()
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        when(this.paymentEventRepository.insertEventIfNotExists(any(), eq(payment.getId()), eq("evt_success"), any(), eq("WEBHOOK"), any()))
                .thenReturn(true);
        when(this.paymentRepository.updateStatusWithGuard(eq(payment.getId()), eq(PaymentStatus.SUCCESS), anyCollection(), any(), eq("fake_pay_success"), eq(false), any(), eq(0L)))
                .thenReturn(1);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("processed");
        verify(this.paymentEventRepository).markEventProcessed("evt_success");
    }

    @Test
    @DisplayName("Verified FAILED payment updates status to FAILED")
    void failedWebhookUpdatesStatus() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.PENDING);
        final byte[] rawBody = createPayloadBytes("evt_fail", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_fail")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_fail",
                PaymentStatus.FAILED,
                new BigDecimal("500.00"),
                "INR",
                "UPI",
                null
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        when(this.paymentEventRepository.insertEventIfNotExists(any(), eq(payment.getId()), eq("evt_fail"), any(), eq("WEBHOOK"), any()))
                .thenReturn(true);
        when(this.paymentRepository.updateStatusWithGuard(eq(payment.getId()), eq(PaymentStatus.FAILED), anyCollection(), any(), eq("fake_pay_fail"), eq(false), any(), eq(0L)))
                .thenReturn(1);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("processed");
        verify(this.paymentEventRepository).markEventProcessed("evt_fail");
    }

    @Test
    @DisplayName("Amount mismatch does NOT mark SUCCESS and sets requires_manual_review=true")
    void amountMismatchSetsReviewFlag() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.PENDING);
        final byte[] rawBody = createPayloadBytes("evt_mismatch", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_mismatch")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        // Mismatched amount: 600.00 instead of 500.00
        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_1",
                PaymentStatus.SUCCESS,
                new BigDecimal("600.00"),
                "INR",
                "UPI",
                Instant.now()
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("mismatch_recorded");
        // Verify update set requiresManualReview = true with reason AMOUNT_MISMATCH
        verify(this.paymentRepository).updateStatusWithGuard(
                eq(payment.getId()),
                eq(PaymentStatus.PENDING),
                anyCollection(),
                eq(null),
                eq(null),
                eq(true),
                eq("AMOUNT_MISMATCH"),
                eq(0L)
        );
    }

    @Test
    @DisplayName("Currency mismatch does NOT mark SUCCESS and sets requires_manual_review=true")
    void currencyMismatchSetsReviewFlag() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.PENDING);
        final byte[] rawBody = createPayloadBytes("evt_curr_mismatch", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_curr_mismatch")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_1",
                PaymentStatus.SUCCESS,
                new BigDecimal("500.00"),
                "USD", // mismatch
                "UPI",
                Instant.now()
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("mismatch_recorded");
        verify(this.paymentRepository).updateStatusWithGuard(
                eq(payment.getId()),
                eq(PaymentStatus.PENDING),
                anyCollection(),
                eq(null),
                eq(null),
                eq(true),
                eq("CURRENCY_MISMATCH"),
                eq(0L)
        );
    }

    @Test
    @DisplayName("Out-of-order event (e.g. FAILED after SUCCESS) is ignored with info log")
    void outOfOrderEventIgnored() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.SUCCESS);
        final byte[] rawBody = createPayloadBytes("evt_late_fail", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_late_fail")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_1",
                PaymentStatus.FAILED,
                new BigDecimal("500.00"),
                "INR",
                "UPI",
                null
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("ignored_out_of_order");
        verify(this.paymentRepository, never()).updateStatusWithGuard(any(), any(), any(), any(), any(), anyBoolean(), any(), anyLong());
    }

    @Test
    @DisplayName("Gateway verification outage throws PaymentGatewayUnavailableException (503) for retry")
    void gatewayOutageThrows503() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.PENDING);
        final byte[] rawBody = createPayloadBytes("evt_retry", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_retry")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId()))
                .thenThrow(new RuntimeException("Connection timeout to gateway"));

        assertThatThrownBy(() -> this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasMessageContaining("Gateway unavailable");
    }

    @Test
    @DisplayName("Late success on EXPIRED payment allows transition to SUCCESS with review flag")
    void latePaymentAllowedWithReviewFlag() throws Exception {
        final Payment payment = createTestPayment(PaymentStatus.EXPIRED);
        final byte[] rawBody = createPayloadBytes("evt_late_success", payment.getGatewayOrderId());

        when(this.paymentGateway.verifyWebhookSignature(rawBody, VALID_SIGNATURE)).thenReturn(true);
        when(this.paymentEventRepository.isEventProcessed("evt_late_success")).thenReturn(false);
        when(this.paymentRepository.findByGatewayOrderId(payment.getGatewayOrderId())).thenReturn(Optional.of(payment));

        final PaymentVerificationResponse verifiedResponse = new PaymentVerificationResponse(
                payment.getGatewayOrderId(),
                "fake_pay_late",
                PaymentStatus.SUCCESS,
                new BigDecimal("500.00"),
                "INR",
                "UPI",
                Instant.now()
        );
        when(this.paymentGateway.verifyPayment(payment.getGatewayOrderId())).thenReturn(verifiedResponse);

        when(this.paymentEventRepository.insertEventIfNotExists(any(), eq(payment.getId()), eq("evt_late_success"), any(), eq("WEBHOOK"), any()))
                .thenReturn(true);
        when(this.paymentRepository.updateStatusWithGuard(eq(payment.getId()), eq(PaymentStatus.SUCCESS), anyCollection(), any(), eq("fake_pay_late"), eq(true), eq("LATE_PAYMENT_AFTER_EXPIRY"), eq(0L)))
                .thenReturn(1);

        final String result = this.webhookService.processWebhook(GATEWAY_NAME, rawBody, VALID_SIGNATURE);

        assertThat(result).isEqualTo("processed");
        verify(this.paymentEventRepository).markEventProcessed("evt_late_success");
    }
}

