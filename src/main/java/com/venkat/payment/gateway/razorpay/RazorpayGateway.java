package com.venkat.payment.gateway.razorpay;

import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.gateway.model.RefundRequest;
import com.venkat.payment.gateway.model.RefundResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production-ready Razorpay gateway adapter implementing the PaymentGateway interface.
 * Uses official Razorpay APIs for single-use dynamic UPI QR creation, verification, QR closure, and refunds.
 */
@Component
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);
    private static final String GATEWAY_NAME = "razorpay";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final RazorpayClient client;
    private final RazorpayStatusMapper statusMapper;
    private final RazorpaySignatureVerifier signatureVerifier;

    public RazorpayGateway(final RazorpayClient client,
                           final RazorpayStatusMapper statusMapper,
                           final RazorpaySignatureVerifier signatureVerifier) {
        this.client = client;
        this.statusMapper = statusMapper;
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    public String gatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public PaymentCreationResponse createPayment(final CreatePaymentGatewayRequest request) {
        final long amountPaise = request.amount().multiply(ONE_HUNDRED).longValue();
        final long closeByEpochSeconds = request.expiresAt().getEpochSecond();

        final RazorpayCreateQrRequest createRequest = new RazorpayCreateQrRequest(
                "upi_qr",
                "Payment for " + request.paymentReference(),
                "single_use",
                true,
                amountPaise,
                "Order: " + request.orderId(),
                closeByEpochSeconds,
                Map.of(
                        "payment_reference", request.paymentReference(),
                        "order_id", request.orderId()
                )
        );

        log.info("Creating Razorpay single-use UPI QR code for payment [{}]", request.paymentReference());
        final RazorpayQrResponse qrResponse = this.client.createQrCode(createRequest);

        // image_content contains the raw upi:// URI string
        final String qrData = qrResponse.imageContent() != null ? qrResponse.imageContent() : "";

        return new PaymentCreationResponse(
                qrResponse.id(),
                qrData,
                request.expiresAt()
        );
    }

    @Override
    public PaymentVerificationResponse verifyPayment(final String gatewayOrderId) {
        log.info("Verifying Razorpay QR payment status for QR ID [{}]", gatewayOrderId);

        // 1. Check if any payments have been recorded against this QR code
        final RazorpayPaymentListResponse paymentsList = this.client.getPaymentsForQr(gatewayOrderId);

        if (paymentsList != null && paymentsList.items() != null && !paymentsList.items().isEmpty()) {
            final RazorpayPaymentItem paymentItem = paymentsList.items().get(0);
            final PaymentStatus status = this.statusMapper.mapPaymentStatus(paymentItem.status());
            final BigDecimal amount = BigDecimal.valueOf(paymentItem.amount()).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
            final Instant paidAt = paymentItem.createdAt() != null ? Instant.ofEpochSecond(paymentItem.createdAt()) : Instant.now();

            return new PaymentVerificationResponse(
                    gatewayOrderId,
                    paymentItem.id(),
                    status,
                    amount,
                    paymentItem.currency(),
                    paymentItem.method() != null ? paymentItem.method() : "UPI",
                    paidAt
            );
        }

        // 2. No payment captured yet; inspect QR code state
        final RazorpayQrResponse qrResponse = this.client.getQrCode(gatewayOrderId);
        final PaymentStatus qrStatus = this.statusMapper.mapQrStatus(qrResponse.status());
        final BigDecimal amount = qrResponse.paymentAmount() != null
                ? BigDecimal.valueOf(qrResponse.paymentAmount()).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new PaymentVerificationResponse(
                gatewayOrderId,
                null,
                qrStatus,
                amount,
                "INR",
                "UPI",
                null
        );
    }

    @Override
    public boolean verifyWebhookSignature(final byte[] rawBody, final String signature) {
        return this.signatureVerifier.verifySignature(rawBody, signature);
    }

    @Override
    public void closePayment(final String gatewayOrderId) {
        log.info("Closing Razorpay QR code [{}]", gatewayOrderId);
        try {
            this.client.closeQrCode(gatewayOrderId);
        } catch (final Exception ex) {
            log.warn("Failed to close Razorpay QR code [{}]: {}", gatewayOrderId, ex.getMessage());
        }
    }

    @Override
    public RefundResponse refund(final RefundRequest request) {
        log.info("Initiating Razorpay refund for payment [{}]", request.gatewayPaymentId());
        final long amountPaise = request.amount().multiply(ONE_HUNDRED).longValue();

        final RazorpayRefundRequest refundRequest = new RazorpayRefundRequest(
                amountPaise,
                request.paymentReference(),
                Map.of("reason", request.reason() != null ? request.reason() : "Customer requested refund")
        );

        final RazorpayRefundResponse response = this.client.createRefund(request.gatewayPaymentId(), refundRequest);
        final BigDecimal refundedAmount = BigDecimal.valueOf(response.amount()).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        final Instant refundedAt = response.createdAt() != null ? Instant.ofEpochSecond(response.createdAt()) : Instant.now();

        return new RefundResponse(
                response.id(),
                response.paymentId(),
                refundedAmount,
                response.currency(),
                response.status(),
                refundedAt
        );
    }
}

