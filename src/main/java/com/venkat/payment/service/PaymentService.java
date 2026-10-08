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
    private static final String IDEMPOTENCY_SCOPE_REFUNDS = "PAYMENT_REFUND";

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStateMachine stateMachine;
    private final PaymentStatusUpdateService statusUpdateService;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentService(final PaymentRepository paymentRepository,
                          final IdempotencyKeyRepository idempotencyRepository,
                          final PaymentGatewayRegistry gatewayRegistry,
                          final PaymentStateMachine stateMachine,
                          final PaymentStatusUpdateService statusUpdateService,
                          final PaymentProperties paymentProperties,
                          final ObjectMapper objectMapper,
                          final Clock clock) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.stateMachine = stateMachine;
        this.statusUpdateService = statusUpdateService;
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

        return mapToResponse(payment);
    }

    /**
     * Cancels an existing unpaid payment session.
     * Verifies with gateway outside transaction first; if already paid, transitions to SUCCESS instead.
     */
    public PaymentResponse cancelPayment(final UUID paymentId, final String userCustomerId, final boolean isInternalUser) {
        final Payment payment = this.paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (!isInternalUser && (userCustomerId == null || !userCustomerId.equals(payment.getCustomerId()))) {
            // Prevent customer enumeration
            log.warn("Customer [{}] attempted to cancel payment [{}] owned by [{}]",
                    userCustomerId, paymentId, payment.getCustomerId());
            throw new PaymentNotFoundException(paymentId);
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            log.info("Payment [{}] already cancelled; returning existing state", payment.getPaymentReference());
            return mapToResponse(payment);
        }

        if (payment.getStatus() != PaymentStatus.CREATED
                && payment.getStatus() != PaymentStatus.QR_GENERATED
                && payment.getStatus() != PaymentStatus.PENDING) {
            throw new InvalidStateTransitionException("Cannot cancel payment in status: " + payment.getStatus());
        }

        // Call gateway outside transaction to ensure customer hasn't paid in the background
        final PaymentGateway gateway = this.gatewayRegistry.getGateway(payment.getGateway());
        if (payment.getGatewayOrderId() != null) {
            final com.venkat.payment.gateway.model.PaymentVerificationResponse verified =
                    gateway.verifyPayment(payment.getGatewayOrderId());

            if (verified.status() == PaymentStatus.SUCCESS) {
                log.warn("Payment [{}] was already paid at gateway! Cannot cancel; transitioning to SUCCESS",
                        payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.SUCCESS,
                        verified.paidAt(),
                        verified.gatewayPaymentId(),
                        false,
                        null,
                        false
                );
                throw new InvalidStateTransitionException("Payment has already been completed and cannot be cancelled");
            }

            // Explicitly close provider-side dynamic QR so it cannot be scanned
            gateway.closePayment(payment.getGatewayOrderId());
        }

        // Transition status to CANCELLED and emit PAYMENT_CANCELLED outbox event
        this.statusUpdateService.transitionStatusWithOutbox(
                payment,
                PaymentStatus.CANCELLED,
                null,
                null,
                false,
                "CANCELLED_BY_CLIENT",
                false
        );

        final Payment updated = this.paymentRepository.findById(paymentId).orElse(payment);
        return mapToResponse(updated);
    }

    /**
     * Processes an idempotent refund against an authoritative SUCCESS payment.
     */
    public com.venkat.payment.api.RefundPaymentResponse refundPayment(
            final UUID paymentId,
            final String idempotencyKey,
            final com.venkat.payment.api.RefundPaymentRequest request) {

        final String requestHash = computeRefundRequestHash(paymentId, request);
        final Instant now = this.clock.instant();
        final Instant keyExpiry = now.plus(this.paymentProperties.getIdempotency().getTtl());

        // 1. Claim idempotency key atomically
        final boolean claimed = this.idempotencyRepository.tryClaimKey(
                IDEMPOTENCY_SCOPE_REFUNDS,
                idempotencyKey,
                requestHash,
                keyExpiry
        );

        if (!claimed) {
            final IdempotencyKeyRepository.IdempotencyRecord existing = this.idempotencyRepository.findByKey(
                    IDEMPOTENCY_SCOPE_REFUNDS,
                    idempotencyKey
            ).orElseThrow(() -> new RequestInProgressException("Refund request currently processing", 2));

            if (!existing.requestHash().equalsIgnoreCase(requestHash)) {
                log.warn("Idempotency key [{}] reused with different refund payload", idempotencyKey);
                throw new IdempotencyKeyReusedException("Idempotency key has already been used with different parameters");
            }

            if ("IN_PROGRESS".equalsIgnoreCase(existing.status())) {
                throw new RequestInProgressException("A refund with this idempotency key is currently processing", 2);
            }

            if ("COMPLETED".equalsIgnoreCase(existing.status()) && existing.responseBody() != null) {
                try {
                    return this.objectMapper.readValue(existing.responseBody(), com.venkat.payment.api.RefundPaymentResponse.class);
                } catch (final Exception e) {
                    log.error("Failed to deserialize cached refund response", e);
                }
            }
        }

        // 2. Fetch payment and validate status
        final Payment payment = this.paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment [{}] already in REFUNDED status", payment.getPaymentReference());
            return new com.venkat.payment.api.RefundPaymentResponse(
                    payment.getId(),
                    PaymentStatus.REFUNDED,
                    payment.getGatewayPaymentId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    payment.getUpdatedAt()
            );
        }

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new InvalidStateTransitionException("Cannot refund payment in status: " + payment.getStatus() + "; payment must be in SUCCESS status");
        }

        // Full refund check (partial refunds documented as future extension)
        final BigDecimal refundAmount = (request != null && request.amount() != null)
                ? request.amount()
                : payment.getAmount();

        if (refundAmount.compareTo(payment.getAmount()) != 0) {
            throw new InvalidInputException("Partial refunds are not currently supported; expected full amount of " + payment.getAmount());
        }

        // 3. Call provider refund API outside DB transaction
        final PaymentGateway gateway = this.gatewayRegistry.getGateway(payment.getGateway());
        final com.venkat.payment.gateway.model.RefundRequest gatewayRequest = new com.venkat.payment.gateway.model.RefundRequest(
                payment.getGatewayPaymentId(),
                refundAmount,
                payment.getCurrency(),
                payment.getPaymentReference(),
                request != null && request.reason() != null ? request.reason() : "Customer requested refund"
        );

        final com.venkat.payment.gateway.model.RefundResponse gatewayResponse;
        try {
            gatewayResponse = gateway.refund(gatewayRequest);
        } catch (final Exception ex) {
            log.error("Refund gateway call failed for payment [{}]", payment.getPaymentReference(), ex);
            throw new PaymentGatewayUnavailableException("Gateway failed to process refund: " + ex.getMessage(), ex);
        }

        // 4. Update status with outbox and complete idempotency key
        final com.venkat.payment.api.RefundPaymentResponse response = new com.venkat.payment.api.RefundPaymentResponse(
                payment.getId(),
                PaymentStatus.REFUNDED,
                gatewayResponse.refundId(),
                gatewayResponse.amount(),
                gatewayResponse.currency(),
                gatewayResponse.refundedAt() != null ? gatewayResponse.refundedAt() : now
        );

        finalizeRefundAndCompleteIdempotency(payment, gatewayResponse.refundId(), idempotencyKey, response);

        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRefundAndCompleteIdempotency(final Payment payment,
                                                    final String refundId,
                                                    final String idempotencyKey,
                                                    final com.venkat.payment.api.RefundPaymentResponse response) {
        this.statusUpdateService.transitionStatusWithOutbox(
                payment,
                PaymentStatus.REFUNDED,
                null,
                refundId,
                false,
                "REFUND: " + refundId,
                false
        );

        try {
            final String responseJson = this.objectMapper.writeValueAsString(response);
            this.idempotencyRepository.completeKey(
                    IDEMPOTENCY_SCOPE_REFUNDS,
                    idempotencyKey,
                    200,
                    responseJson,
                    payment.getId()
            );
        } catch (final Exception e) {
            log.error("Failed to cache refund response in idempotency record", e);
        }
    }

    public static String computeRefundRequestHash(final UUID paymentId, final com.venkat.payment.api.RefundPaymentRequest request) {
        final String canonicalString = String.format("REFUND:%s:%s:%s",
                paymentId,
                request != null && request.amount() != null ? request.amount().setScale(2).toPlainString() : "FULL",
                request != null && request.reason() != null ? request.reason().trim() : ""
        );
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private PaymentResponse mapToResponse(final Payment payment) {
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
