package com.venkat.payment.service;

import com.venkat.payment.api.CreatePaymentRequest;
import com.venkat.payment.api.CreatePaymentResponse;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentStateMachine stateMachine;
    private PaymentProperties paymentProperties;
    private Clock fixedClock;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        this.stateMachine = new PaymentStateMachine();
        this.paymentProperties = new PaymentProperties();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.paymentService = new PaymentService(
                this.paymentRepository,
                this.gatewayRegistry,
                this.stateMachine,
                this.paymentProperties,
                this.fixedClock
        );
    }

    @Test
    @DisplayName("Create payment success transitions payment to PENDING and returns QR data")
    void createPaymentSuccess() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentRepository.insert(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final PaymentCreationResponse gwResponse = new PaymentCreationResponse("fake_ord_1", "upi://pay?...", this.fixedClock.instant().plusSeconds(900));
        when(this.paymentGateway.createPayment(any())).thenReturn(gwResponse);
        when(this.paymentRepository.updateGatewayDetailsAndStatus(any(), eq("fake_ord_1"), eq("upi://pay?..."), eq(PaymentStatus.PENDING), eq(0L)))
                .thenReturn(1);

        final CreatePaymentResponse response = this.paymentService.createPayment(request);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.amount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.paymentReference()).startsWith("PAY-");
        assertThat(response.qrCode()).isEqualTo("upi://pay?...");
    }

    @Test
    @DisplayName("Create payment marks FAILED in DB and throws 503 when gateway call fails")
    void createPaymentGatewayFailure() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentRepository.insert(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.paymentGateway.createPayment(any())).thenThrow(new RuntimeException("Timeout connecting to provider"));

        assertThatThrownBy(() -> this.paymentService.createPayment(request))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasMessageContaining("Payment gateway creation failed");

        verify(this.paymentRepository).markFailed(any(), any(), eq(0L));
    }

    @Test
    @DisplayName("Disallowed currency throws InvalidInputException (400)")
    void disallowedCurrencyFails() {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "EUR", "CUST-1");

        assertThatThrownBy(() -> this.paymentService.createPayment(request))
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
}

