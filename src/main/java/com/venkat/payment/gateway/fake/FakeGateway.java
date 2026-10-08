package com.venkat.payment.gateway.fake;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory simulation gateway providing deterministic UPI QR generation,
 * state persistence for simulator testing, and HMAC-SHA256 webhook signature validation.
 */
@Component
public class FakeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    private final PaymentProperties paymentProperties;
    private final Map<String, FakeOrderState> orders = new ConcurrentHashMap<>();

    public FakeGateway(final PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    @Override
    public String gatewayName() {
        return "fake";
    }

    @Override
    public PaymentCreationResponse createPayment(final CreatePaymentGatewayRequest request) {
        final String gatewayOrderId = "fake_ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final String formattedAmount = request.amount().setScale(2).toPlainString();
        final String qrData = String.format(
                "upi://pay?pa=fake@bank&pn=FakeMerchant&am=%s&cu=%s&tn=%s",
                formattedAmount,
                request.currency(),
                request.paymentReference()
        );

        final FakeOrderState initialState = new FakeOrderState(
                gatewayOrderId,
                request.paymentReference(),
                request.amount(),
                request.currency(),
                PaymentStatus.PENDING,
                null,
                null,
                "UPI"
        );
        this.orders.put(gatewayOrderId, initialState);

        log.info("FakeGateway created order: gatewayOrderId={}, ref={}, qrData={}",
                gatewayOrderId, request.paymentReference(), qrData);

        return new PaymentCreationResponse(gatewayOrderId, qrData, request.expiresAt());
    }

    @Override
    public PaymentVerificationResponse verifyPayment(final String gatewayOrderId) {
        final FakeOrderState state = this.orders.get(gatewayOrderId);
        if (state == null) {
            log.warn("FakeGateway order not found for gatewayOrderId={}", gatewayOrderId);
            return new PaymentVerificationResponse(
                    gatewayOrderId,
                    null,
                    PaymentStatus.PENDING,
                    BigDecimal.ZERO,
                    "INR",
                    "UPI",
                    null
            );
        }

        // Unknown or null gateway status must default to PENDING (never SUCCESS)
        final PaymentStatus resolvedStatus = (state.status() != null) ? state.status() : PaymentStatus.PENDING;

        return new PaymentVerificationResponse(
                state.gatewayOrderId(),
                state.gatewayPaymentId(),
                resolvedStatus,
                state.amount(),
                state.currency(),
                state.paymentMethod(),
                state.paidAt()
        );
    }

    @Override
    public boolean verifyWebhookSignature(final byte[] rawBody, final String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }

        final String secret = this.paymentProperties.getFakeGatewayWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("FakeGateway webhook secret is not configured!");
            return false;
        }

        final String computedSignature = calculateHmacSha256(rawBody, secret);
        if (computedSignature == null) {
            return false;
        }

        final byte[] expectedBytes = computedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8);
        final byte[] actualBytes = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    /**
     * Computes lowercase hex-encoded HMAC-SHA256 signature for the given raw bytes and secret.
     */
    public static String calculateHmacSha256(final byte[] data, final String secret) {
        try {
            final Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            final SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
            mac.init(secretKeySpec);
            final byte[] rawHmac = mac.doFinal(data);
            return HexFormat.of().formatHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to calculate HMAC-SHA256 signature", e);
            return null;
        }
    }

    /**
     * Allows simulator to mutate fake order state for testing various outcomes.
     */
    public void setOrderState(final String gatewayOrderId,
                              final PaymentStatus status,
                              final String gatewayPaymentId,
                              final Instant paidAt,
                              final BigDecimal overrideAmount,
                              final String overrideCurrency) {
        final FakeOrderState existing = this.orders.get(gatewayOrderId);
        if (existing == null) {
            throw new IllegalArgumentException("FakeGateway order not found: " + gatewayOrderId);
        }

        final FakeOrderState updated = new FakeOrderState(
                gatewayOrderId,
                existing.paymentReference(),
                overrideAmount != null ? overrideAmount : existing.amount(),
                overrideCurrency != null ? overrideCurrency : existing.currency(),
                status,
                gatewayPaymentId != null ? gatewayPaymentId : "fake_pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                paidAt != null ? paidAt : (status == PaymentStatus.SUCCESS ? Instant.now() : null),
                existing.paymentMethod()
        );
        this.orders.put(gatewayOrderId, updated);
        log.info("FakeGateway order updated: gatewayOrderId={}, status={}, amount={}",
                gatewayOrderId, updated.status(), updated.amount());
    }

    public FakeOrderState getOrderState(final String gatewayOrderId) {
        return this.orders.get(gatewayOrderId);
    }

    public record FakeOrderState(
            String gatewayOrderId,
            String paymentReference,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String gatewayPaymentId,
            Instant paidAt,
            String paymentMethod
    ) {
    }
}

