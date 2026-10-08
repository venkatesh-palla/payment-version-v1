package com.venkat.payment.gateway.razorpay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Razorpay payment gateway integration.
 * In production, all secrets must be provided via environment variables.
 */
@Component
@ConfigurationProperties(prefix = "payment.gateways.razorpay")
public class RazorpayProperties {

    private String baseUrl = "https://api.razorpay.com/v1";
    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private Duration timeout = Duration.ofSeconds(5);
    private Duration replayTolerance = Duration.ofMinutes(5);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(final String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(final String keySecret) {
        this.keySecret = keySecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(final String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(final Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getReplayTolerance() {
        return replayTolerance;
    }

    public void setReplayTolerance(final Duration replayTolerance) {
        this.replayTolerance = replayTolerance;
    }
}
