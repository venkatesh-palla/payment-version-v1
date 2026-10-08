package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.CreatePaymentRequest;
import com.venkat.payment.api.CreatePaymentResponse;
import com.venkat.payment.api.IdempotencyKeyReusedException;
import com.venkat.payment.api.InvalidInputException;
import com.venkat.payment.api.PaymentGatewayUnavailableException;
import com.venkat.payment.api.PaymentNotFoundException;
import com.venkat.payment.api.PaymentResponse;
import com.venkat.payment.api.RequestInProgressException;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.repository.IdempotencyKeyRepository;
import com.venkat.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core business service orchestrating idempotent payment creation,
 * gateway interaction outside DB transactions, and transactional persistence.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String IDEMPOTENCY_SCOPE_PAYMENTS = "PAYMENT_CREATE";

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStateMachine stateMachine;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentService(final PaymentRepository paymentRepository,
                          final IdempotencyKeyRepository idempotencyRepository,
                          final PaymentGatewayRegistry gatewayRegistry,
                          final PaymentStateMachine stateMachine,
                          final PaymentProperties paymentProperties,
                          final ObjectMapper objectMapper,
                          final Clock clock) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.stateMachine = stateMachine;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Executes the idempotent end-to-end payment creation flow.
     */
    public CreatePaymentResponse createPayment(final String idempotencyKey, final CreatePaymentRequest request) {
        validateRequest(request);

        final String requestHash = computeRequestHash(request);
        final Instant now = this.clock.instant();
        final Instant keyExpiry = now.plus(this.paymentProperties.getIdempotency().getTtl());

        // 1. Claim idempotency key atomically
        final boolean claimed = this.idempotencyRepository.tryClaimKey(
                IDEMPOTENCY_SCOPE_PAYMENTS,
                idempotencyKey,
                requestHash,
                keyExpiry
        );

        if (!claimed) {
            // Key already exists - evaluate state
            final IdempotencyKeyRepository.IdempotencyRecord existing = this.idempotencyRepository.findByKey(
                    IDEMPOTENCY_SCOPE_PAYMENTS,
                    idempotencyKey
            ).orElseThrow(() -> new RequestInProgressException("Request currently in progress", 2));

            // Check if request payload matches
            if (!existing.requestHash().equalsIgnoreCase(requestHash)) {
                log.warn("Idempotency key [{}] reused with different request payload", idempotencyKey);
                throw new IdempotencyKeyReusedException("Idempotency key has already been used with different parameters");
            }

            if ("IN_PROGRESS".equalsIgnoreCase(existing.status())) {
                log.info("Concurrent request with key [{}] currently in progress", idempotencyKey);
                throw new RequestInProgressException("A request with this idempotency key is currently processing", 2);
            }

            // Return cached response if COMPLETED
            if ("COMPLETED".equalsIgnoreCase(existing.status()) && existing.responseBody() != null) {
                try {
                    log.info("Returning cached idempotent response for key [{}]", idempotencyKey);
                    return this.objectMapper.readValue(existing.responseBody(), CreatePaymentResponse.class);
                } catch (final Exception e) {
                    log.error("Failed to deserialize cached response for key [{}]", idempotencyKey, e);
                }
            }
        }

        // Proceed with payment creation
        final String paymentReference = generatePaymentReference();
        final Instant expiresAt = now.plus(this.paymentProperties.getQr().getExpiry());
        final String gatewayName = this.paymentProperties.getGateway();
        final PaymentGateway gateway = this.gatewayRegistry.getGateway(gatewayName);

        final Payment createdPayment;
        try {
            createdPayment = saveInitialPayment(request, paymentReference, gatewayName, expiresAt, now);
        } catch (final DuplicateKeyException dke) {
            log.warn("Active payment already exists for orderId: {}", request.orderId());
            throw new InvalidInputException("An active payment already exists for order: " + request.orderId());
        }

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
            markPaymentFailed(paymentId, "GATEWAY_ERROR: " + ex.getMessage(), createdPayment.getVersion());
            throw new PaymentGatewayUnavailableException("Payment gateway creation failed: " + ex.getMessage(), ex);
        }

        // 3. Finalize payment & complete idempotency key in same transaction
        final CreatePaymentResponse response = new CreatePaymentResponse(
                paymentId,
                paymentReference,
                PaymentStatus.PENDING,
                request.amount(),
                request.currency(),
                creationResponse.qrData(),
                expiresAt
        );

        finalizePaymentAndCompleteIdempotency(
                paymentId,
                creationResponse.gatewayOrderId(),
                creationResponse.qrData(),
                createdPayment.getVersion(),
                idempotencyKey,
                response
        );

        return response;
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

    public Payment getPaymentEntity(final UUID paymentId) {
        return this.paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
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
    public void finalizePaymentAndCompleteIdempotency(final UUID paymentId,
                                                     final String gatewayOrderId,
                                                     final String qrData,
                                                     final long expectedVersion,
                                                     final String idempotencyKey,
                                                     final CreatePaymentResponse response) {
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

        try {
            final String responseJson = this.objectMapper.writeValueAsString(response);
            this.idempotencyRepository.completeKey(
                    IDEMPOTENCY_SCOPE_PAYMENTS,
                    idempotencyKey,
                    201,
                    responseJson,
                    paymentId
            );
        } catch (final Exception e) {
            log.error("Failed to cache response in idempotency record for key [{}]", idempotencyKey, e);
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

    public static String computeRequestHash(final CreatePaymentRequest request) {
        final String canonicalString = String.format("%s:%s:%s:%s",
                request.orderId().trim(),
                request.amount().setScale(2).toPlainString(),
                request.currency().trim().toUpperCase(),
                request.customerId() != null ? request.customerId().trim() : ""
        );
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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
