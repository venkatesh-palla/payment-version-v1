package com.venkat.payment.gateway;

import com.venkat.payment.gateway.model.CreatePaymentGatewayRequest;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;

/**
 * Common abstraction implemented by all payment gateways.
 */
public interface PaymentGateway {

    /**
     * Unique identifier for the gateway (e.g. "fake", "razorpay", "cashfree").
     *
     * @return gateway name identifier
     */
    String gatewayName();

    /**
     * Initiates a payment session and returns QR code data with gateway identifiers.
     *
     * @param request creation parameters
     * @return creation response containing order id and QR string
     */
    PaymentCreationResponse createPayment(CreatePaymentGatewayRequest request);

    /**
     * Verifies the authoritative state of a payment with the provider.
     *
     * @param gatewayOrderId provider order identifier
     * @return authoritative payment verification details
     */
    PaymentVerificationResponse verifyPayment(String gatewayOrderId);

    /**
     * Verifies the HMAC signature of incoming raw webhook bytes.
     *
     * @param rawBody   unparsed webhook body payload
     * @param signature provider signature header
     * @return true if valid, false otherwise
     */
    boolean verifyWebhookSignature(byte[] rawBody, String signature);

    /**
     * Closes an active single-use QR code or payment session at the provider upon cancellation or expiration.
     * Optional operation; default is a no-op if unsupported by provider.
     *
     * @param gatewayOrderId provider order/session/QR identifier
     */
    default void closePayment(String gatewayOrderId) {
        // Default no-op for providers that do not support explicit QR closure
    }

    /**
     * Initiates a refund for a previously captured payment.
     *
     * @param request refund details
     * @return refund response
     */
    default com.venkat.payment.gateway.model.RefundResponse refund(com.venkat.payment.gateway.model.RefundRequest request) {
        throw new UnsupportedOperationException("Refund is not supported by " + gatewayName());
    }
}

