package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.CreatePaymentRequest;
import com.venkat.payment.api.CreatePaymentResponse;
import com.venkat.payment.api.IdempotencyKeyReusedException;
import com.venkat.payment.api.InvalidInputException;
import com.venkat.payment.api.PaymentGatewayUnavailableException;
import com.venkat.payment.api.PaymentNotFoundException;
import com.venkat.payment.api.PaymentResponse;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.repository.IdempotencyKeyRepository;
import com.venkat.payment.repository.IdempotencyKeyRepository.IdempotencyRecord;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentStatusUpdateService statusUpdateService;

    private PaymentStateMachine stateMachine;
    private PaymentProperties paymentProperties;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private PaymentService paymentService;

    private static final String IDEMPOTENCY_KEY = "KEY-TEST-12345678";

    @BeforeEach
    void setUp() {
        this.stateMachine = new PaymentStateMachine();
        this.paymentProperties = new PaymentProperties();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.paymentService = new PaymentService(
                this.paymentRepository,
                this.idempotencyRepository,
                this.gatewayRegistry,
                this.stateMachine,
                this.statusUpdateService,
                this.paymentProperties,
                this.objectMapper,
                this.fixedClock
        );
    }

    @Test
    @DisplayName("Create payment success transitions payment to PENDING and returns QR data")
    void createPaymentSuccess() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        when(this.idempotencyRepository.tryClaimKey(anyString(), eq(IDEMPOTENCY_KEY), anyString(), any())).thenReturn(true);
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentRepository.insert(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final PaymentCreationResponse gwResponse = new PaymentCreationResponse("fake_ord_1", "upi://pay?...", this.fixedClock.instant().plusSeconds(900));
        when(this.paymentGateway.createPayment(any())).thenReturn(gwResponse);
        when(this.paymentRepository.updateGatewayDetailsAndStatus(any(), eq("fake_ord_1"), eq("upi://pay?..."), eq(PaymentStatus.PENDING), eq(0L)))
                .thenReturn(1);

        final CreatePaymentResponse response = this.paymentService.createPayment(IDEMPOTENCY_KEY, request);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.amount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.paymentReference()).startsWith("PAY-");
        assertThat(response.qrCode()).isEqualTo("upi://pay?...");
        verify(this.idempotencyRepository).completeKey(anyString(), eq(IDEMPOTENCY_KEY), eq(201), anyString(), any());
    }

    @Test
    @DisplayName("Idempotent create with same key and same body returns cached response")
    void createPaymentIdempotentCached() throws Exception {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");
        final String requestHash = PaymentService.computeRequestHash(request);

        final CreatePaymentResponse cachedResponse = new CreatePaymentResponse(
                UUID.randomUUID(), "PAY-CACHED12345", PaymentStatus.PENDING, new BigDecimal("500.00"), "INR", "upi://...", Instant.now().plusSeconds(900)
        );
        final String cachedJson = this.objectMapper.writeValueAsString(cachedResponse);

        when(this.idempotencyRepository.tryClaimKey(anyString(), eq(IDEMPOTENCY_KEY), eq(requestHash), any())).thenReturn(false);
        final IdempotencyRecord existingRecord = new IdempotencyRecord(
                "PAYMENT_CREATE", IDEMPOTENCY_KEY, requestHash, "COMPLETED", 201, cachedJson, cachedResponse.paymentId(), Instant.now(), Instant.now().plusSeconds(3600)
        );
        when(this.idempotencyRepository.findByKey(anyString(), eq(IDEMPOTENCY_KEY))).thenReturn(Optional.of(existingRecord));

        final CreatePaymentResponse response = this.paymentService.createPayment(IDEMPOTENCY_KEY, request);

        assertThat(response.paymentReference()).isEqualTo("PAY-CACHED12345");
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Idempotent create with same key but different body throws 422 IDEMPOTENCY_KEY_REUSED")
    void createPaymentIdempotentKeyReusedDifferentBody() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");
        final String currentHash = PaymentService.computeRequestHash(request);

        when(this.idempotencyRepository.tryClaimKey(anyString(), eq(IDEMPOTENCY_KEY), eq(currentHash), any())).thenReturn(false);
        final IdempotencyRecord existingRecord = new IdempotencyRecord(
                "PAYMENT_CREATE", IDEMPOTENCY_KEY, "different_sha256_hash", "COMPLETED", 201, "{}", UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(3600)
        );
        when(this.idempotencyRepository.findByKey(anyString(), eq(IDEMPOTENCY_KEY))).thenReturn(Optional.of(existingRecord));

        assertThatThrownBy(() -> this.paymentService.createPayment(IDEMPOTENCY_KEY, request))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasMessageContaining("Idempotency key has already been used with different parameters");
    }

    @Test
    @DisplayName("Create payment marks FAILED in DB and throws 503 when gateway call fails")
    void createPaymentGatewayFailure() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        when(this.idempotencyRepository.tryClaimKey(anyString(), eq(IDEMPOTENCY_KEY), anyString(), any())).thenReturn(true);
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentRepository.insert(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.paymentGateway.createPayment(any())).thenThrow(new RuntimeException("Timeout connecting to provider"));

        assertThatThrownBy(() -> this.paymentService.createPayment(IDEMPOTENCY_KEY, request))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasMessageContaining("Payment gateway creation failed");

        verify(this.paymentRepository).markFailed(any(), any(), eq(0L));
    }

    @Test
    @DisplayName("Disallowed currency throws InvalidInputException (400)")
    void disallowedCurrencyFails() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "EUR", "CUST-1");

        assertThatThrownBy(() -> this.paymentService.createPayment(IDEMPOTENCY_KEY, request))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("Currency EUR is not allowed");
    }

    @Test
    @DisplayName("Get payment returns response when found")
    void getPaymentSuccess() {
        final UUID id = UUID.randomUUID();
        final Payment payment = new Payment(
                id, "PAY-REF1", "ORD-1", "CUST-1", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", "fake_pay_1", PaymentStatus.SUCCESS, "UPI", "upi://...",
                Instant.now().plusSeconds(600), Instant.now(), false, null, 0, null, 1L, Instant.now(), Instant.now()
        );
        when(this.paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        final PaymentResponse response = this.paymentService.getPayment(id);
        assertThat(response.paymentId()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("Get payment throws PaymentNotFoundException (404) when not found")
    void getPaymentNotFound() {
        final UUID id = UUID.randomUUID();
        when(this.paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.paymentService.getPayment(id))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Cancel payment when unpaid closes provider QR and transitions to CANCELLED")
    void cancelPaymentSuccess() {
        final UUID id = UUID.randomUUID();
        final Payment payment = new Payment(
                id, "PAY-REF-CANCEL", "ORD-1", "CUST-1", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_cancel", null, PaymentStatus.PENDING, "UPI", "upi://...",
                Instant.now().plusSeconds(600), null, false, null, 0, null, 1L, Instant.now(), Instant.now()
        );
        when(this.paymentRepository.findById(id)).thenReturn(Optional.of(payment));
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentGateway.verifyPayment("fake_ord_cancel"))
                .thenReturn(new com.venkat.payment.gateway.model.PaymentVerificationResponse(
                        "fake_ord_cancel", null, PaymentStatus.PENDING, new BigDecimal("500.00"), "INR", "UPI", null));

        final PaymentResponse response = this.paymentService.cancelPayment(id, "CUST-1", false);

        verify(this.paymentGateway).closePayment("fake_ord_cancel");
        verify(this.statusUpdateService).transitionStatusWithOutbox(
                eq(payment), eq(PaymentStatus.CANCELLED), eq(null), eq(null), eq(false), eq("CANCELLED_BY_CLIENT"), eq(false));
    }

    @Test
    @DisplayName("Cancel payment when already paid at gateway transitions to SUCCESS and rejects cancellation")
    void cancelPaymentWhenPaidAtGatewayTransitionsToSuccessAndThrows() {
        final UUID id = UUID.randomUUID();
        final Payment payment = new Payment(
                id, "PAY-REF-PAID", "ORD-1", "CUST-1", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_paid", null, PaymentStatus.PENDING, "UPI", "upi://...",
                Instant.now().plusSeconds(600), null, false, null, 0, null, 1L, Instant.now(), Instant.now()
        );
        final Instant paidAt = Instant.now().minusSeconds(10);
        when(this.paymentRepository.findById(id)).thenReturn(Optional.of(payment));
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentGateway.verifyPayment("fake_ord_paid"))
                .thenReturn(new com.venkat.payment.gateway.model.PaymentVerificationResponse(
                        "fake_ord_paid", "fake_pay_1", PaymentStatus.SUCCESS, new BigDecimal("500.00"), "INR", "UPI", paidAt));

        assertThatThrownBy(() -> this.paymentService.cancelPayment(id, "CUST-1", false))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Payment has already been completed and cannot be cancelled");

        verify(this.statusUpdateService).transitionStatusWithOutbox(
                eq(payment), eq(PaymentStatus.SUCCESS), eq(paidAt), eq("fake_pay_1"), eq(false), eq(null), eq(false));
    }

    @Test
    @DisplayName("Refund payment success calls gateway and records outbox event")
    void refundPaymentSuccess() {
        final UUID id = UUID.randomUUID();
        final Payment payment = new Payment(
                id, "PAY-REF-RFND", "ORD-1", "CUST-1", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", "fake_pay_1", PaymentStatus.SUCCESS, "UPI", "upi://...",
                Instant.now().plusSeconds(600), Instant.now(), false, null, 0, null, 1L, Instant.now(), Instant.now()
        );
        final String idempotencyKey = "REFUND-KEY-12345678";
        final com.venkat.payment.api.RefundPaymentRequest request =
                new com.venkat.payment.api.RefundPaymentRequest(new BigDecimal("500.00"), "Customer return");

        when(this.idempotencyRepository.tryClaimKey(any(), eq(idempotencyKey), any(), any())).thenReturn(true);
        when(this.paymentRepository.findById(id)).thenReturn(Optional.of(payment));
        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentGateway.refund(any())).thenReturn(
                new com.venkat.payment.gateway.model.RefundResponse("rfnd_1", "fake_pay_1", new BigDecimal("500.00"), "INR", "processed", Instant.now())
        );

        final com.venkat.payment.api.RefundPaymentResponse response =
                this.paymentService.refundPayment(id, idempotencyKey, request);

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundId()).isEqualTo("rfnd_1");
        assertThat(response.refundedAmount()).isEqualTo(new BigDecimal("500.00"));
        verify(this.statusUpdateService).transitionStatusWithOutbox(
                eq(payment), eq(PaymentStatus.REFUNDED), eq(null), eq("rfnd_1"), eq(false), eq("REFUND: rfnd_1"), eq(false));
    }

    @Test
    @DisplayName("Refund payment when not in SUCCESS throws InvalidStateTransitionException")
    void refundPaymentWhenNotSuccessThrows() {
        final UUID id = UUID.randomUUID();
        final Payment payment = new Payment(
                id, "PAY-REF-PENDING", "ORD-1", "CUST-1", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", null, PaymentStatus.PENDING, "UPI", "upi://...",
                Instant.now().plusSeconds(600), null, false, null, 0, null, 1L, Instant.now(), Instant.now()
        );
        final String idempotencyKey = "REFUND-KEY-PENDING";
        when(this.idempotencyRepository.tryClaimKey(any(), eq(idempotencyKey), any(), any())).thenReturn(true);
        when(this.paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> this.paymentService.refundPayment(id, idempotencyKey, null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot refund payment in status: PENDING");
    }
}
