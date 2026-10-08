package com.venkat.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing a payment record.
 */
public class Payment {

    private UUID id;
    private String paymentReference;
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String gateway;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private PaymentStatus status;
    private String paymentMethod;
    private String qrData;
    private Instant expiresAt;
    private Instant paidAt;
    private boolean requiresManualReview;
    private String reviewReason;
    private int verificationAttempts;
    private Instant nextVerificationAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public Payment() {
    }

    public Payment(final UUID id,
                   final String paymentReference,
                   final String orderId,
                   final String customerId,
                   final BigDecimal amount,
                   final String currency,
                   final String gateway,
                   final String gatewayOrderId,
                   final String gatewayPaymentId,
                   final PaymentStatus status,
                   final String paymentMethod,
                   final String qrData,
                   final Instant expiresAt,
                   final Instant paidAt,
                   final boolean requiresManualReview,
                   final String reviewReason,
                   final int verificationAttempts,
                   final Instant nextVerificationAt,
                   final long version,
                   final Instant createdAt,
                   final Instant updatedAt) {
        this.id = id;
        this.paymentReference = paymentReference;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.gateway = gateway;
        this.gatewayOrderId = gatewayOrderId;
        this.gatewayPaymentId = gatewayPaymentId;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.qrData = qrData;
        this.expiresAt = expiresAt;
        this.paidAt = paidAt;
        this.requiresManualReview = requiresManualReview;
        this.reviewReason = reviewReason;
        this.verificationAttempts = verificationAttempts;
        this.nextVerificationAt = nextVerificationAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(final String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(final String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(final String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(final String currency) {
        this.currency = currency;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(final String gateway) {
        this.gateway = gateway;
    }

    public String getGatewayOrderId() {
        return gatewayOrderId;
    }

    public void setGatewayOrderId(final String gatewayOrderId) {
        this.gatewayOrderId = gatewayOrderId;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public void setGatewayPaymentId(final String gatewayPaymentId) {
        this.gatewayPaymentId = gatewayPaymentId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(final PaymentStatus status) {
        this.status = status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(final String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getQrData() {
        return qrData;
    }

    public void setQrData(final String qrData) {
        this.qrData = qrData;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(final Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(final Instant paidAt) {
        this.paidAt = paidAt;
    }

    public boolean isRequiresManualReview() {
        return requiresManualReview;
    }

    public void setRequiresManualReview(final boolean requiresManualReview) {
        this.requiresManualReview = requiresManualReview;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(final String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public int getVerificationAttempts() {
        return verificationAttempts;
    }

    public void setVerificationAttempts(final int verificationAttempts) {
        this.verificationAttempts = verificationAttempts;
    }

    public Instant getNextVerificationAt() {
        return nextVerificationAt;
    }

    public void setNextVerificationAt(final Instant nextVerificationAt) {
        this.nextVerificationAt = nextVerificationAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(final long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

