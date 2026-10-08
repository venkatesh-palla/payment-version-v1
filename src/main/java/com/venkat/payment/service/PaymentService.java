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
import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core business service orchestrating payment creation, gateway interaction outside of DB transactions,
 * and state persistence.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStateMachine stateMachine;
    private final PaymentProperties paymentProperties;
    private final Clock clock;

    public PaymentService(final PaymentRepository paymentRepository,
                          final PaymentGatewayRegistry gatewayRegistry,
                          final PaymentStateMachine stateMachine,
                          final PaymentProperties paymentProperties,
                          final Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.stateMachine = stateMachine;
        this.paymentProperties = paymentProperties;
        this.clock = clock;
    }

    /**
     * Executes the end-to-end payment creation flow:
     * 1. Persist initial payment record in state CREATED (Tx 1).
     * 2. Invoke PaymentGateway outside of any database transaction.
     * 3. Transition CREATED -> QR_GENERATED -> PENDING and persist QR data (Tx 2).
     */
    public CreatePaymentResponse createPayment(final CreatePaymentRequest request) {
        validateRequest(request);

        final String paymentReference = generatePaymentReference();
        final Instant now = this.clock.instant();
        final Instant expiresAt = now.plus(this.paymentProperties.getQr().getExpiry());
        final String gatewayName = this.paymentProperties.getGateway();
        final PaymentGateway gateway = this.gatewayRegistry.getGateway(gatewayName);

        // 1. Save payment as CREATED in own transaction
        final Payment createdPayment = saveInitialPayment(request, paymentReference, gatewayName, expiresAt, now);
        final UUID paymentId = createdPayment.getId();

        PaymentCreationResponse creationResponse;
        try {
            // 2. Call gateway WITHOUT holding a database transaction
            final CreatePaymentGatewayRequest gatewayRequest = new CreatePaymentGatewayRequest(
                    paymentReference,
                    request.amount(),
                    request.currency(),
                    request.orderId(),
                    expiresAt
            );
            creationResponse = gateway.createPayment(gatewayRequest);
        } catch (final Exception ex) {
            log.error("Payment gateway [{}] call failed for payment reference {}", gatewayName, paymentReference, ex);
            // On failure, mark FAILED in DB and return 503
            markPaymentFailed(paymentId, "GATEWAY_ERROR: " + ex.getMessage(), createdPayment.getVersion());
            throw new PaymentGatewayUnavailableException("Payment gateway creation failed: " + ex.getMessage(), ex);
        }

        // 3. Save gateway ids + qr and transition CREATED -> QR_GENERATED -> PENDING in own transaction
        this.stateMachine.validate(PaymentStatus.CREATED, PaymentStatus.QR_GENERATED);
        this.stateMachine.validate(PaymentStatus.QR_GENERATED, PaymentStatus.PENDING);

        finalizePaymentCreation(paymentId, creationResponse.gatewayOrderId(), creationResponse.qrData(), createdPayment.getVersion());

        return new CreatePaymentResponse(
                paymentId,
                paymentReference,
                PaymentStatus.PENDING,
                request.amount(),
                request.currency(),
                creationResponse.qrData(),
                expiresAt
        );
    }

    public PaymentResponse getPayment(final UUID paymentId) {
        final Payment payment = this.paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getOrderId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaidAt(),
                payment.getExpiresAt()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveInitialPayment(final CreatePaymentRequest request,
                                      final String paymentReference,
                                      final String gatewayName,
                                      final Instant expiresAt,
                                      final Instant now) {
        final Payment payment = new Payment(
                UUID.randomUUID(),
                paymentReference,
                request.orderId(),
                request.customerId(),
                request.amount(),
                request.currency().toUpperCase(),
                gatewayName,
                null,
                null,
                PaymentStatus.CREATED,
                "UPI",
                null,
                expiresAt,
                null,
                false,
                null,
                0,
                null,
                0L,
                now,
                now
        );
        return this.paymentRepository.insert(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizePaymentCreation(final UUID paymentId,
                                        final String gatewayOrderId,
                                        final String qrData,
                                        final long expectedVersion) {
        final int updated = this.paymentRepository.updateGatewayDetailsAndStatus(
                paymentId,
                gatewayOrderId,
                qrData,
                PaymentStatus.PENDING,
                expectedVersion
        );
        if (updated == 0) {
            throw new IllegalStateException("Optimistic lock conflict when updating payment " + paymentId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaymentFailed(final UUID paymentId, final String reason, final long expectedVersion) {
        this.paymentRepository.markFailed(paymentId, reason, expectedVersion);
    }

    private void validateRequest(final CreatePaymentRequest request) {
        if (!this.paymentProperties.getCurrency().getAllowed().contains(request.currency().toUpperCase())) {
            throw new InvalidInputException(String.format("Currency %s is not allowed. Supported: %s",
                    request.currency(), this.paymentProperties.getCurrency().getAllowed()));
        }
        if (request.amount().compareTo(this.paymentProperties.getMaxAmount()) > 0) {
            throw new InvalidInputException(String.format("Amount exceeds maximum allowed limit of %s",
                    this.paymentProperties.getMaxAmount()));
        }
    }

    public static String generatePaymentReference() {
        final StringBuilder sb = new StringBuilder("PAY-");
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}

