package com.venkat.payment.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration properties for the payment service (Level 2).
 */
@Validated
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    @NotBlank
    private String gateway = "fake";

    @NotBlank
    private String apiKey = "dev-api-key-12345";

    @NotBlank
    private String fakeGatewayWebhookSecret = "fake-webhook-secret-key-12345";

    private String devJwtSecret = "local-dev-jwt-secret-key-must-be-at-least-32-bytes-long!";

    @NotNull
    private CurrencyConfig currency = new CurrencyConfig();

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal maxAmount = new BigDecimal("100000.00");

    private boolean allowMultipleActivePerOrder = false;

    @NotNull
    private QrConfig qr = new QrConfig();

    @NotNull
    private DevConfig dev = new DevConfig();

    @NotNull
    private IdempotencyConfig idempotency = new IdempotencyConfig();

    @NotNull
    private OutboxConfig outbox = new OutboxConfig();

    @NotNull
    private EventsConfig events = new EventsConfig();

    @NotNull
    private BusinessProcessConfig businessProcess = new BusinessProcessConfig();

    @NotNull
    private ExpiryConfig expiry = new ExpiryConfig();

    @NotNull
    private VerificationConfig verification = new VerificationConfig();

    public String getGateway() {
        return gateway;
    }

    public void setGateway(final String gateway) {
        this.gateway = gateway;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(final String apiKey) {
        this.apiKey = apiKey;
    }

    public String getFakeGatewayWebhookSecret() {
        return fakeGatewayWebhookSecret;
    }

    public void setFakeGatewayWebhookSecret(final String fakeGatewayWebhookSecret) {
        this.fakeGatewayWebhookSecret = fakeGatewayWebhookSecret;
    }

    public String getDevJwtSecret() {
        return devJwtSecret;
    }

    public void setDevJwtSecret(final String devJwtSecret) {
        this.devJwtSecret = devJwtSecret;
    }

    public CurrencyConfig getCurrency() {
        return currency;
    }

    public void setCurrency(final CurrencyConfig currency) {
        this.currency = currency;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(final BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    public boolean isAllowMultipleActivePerOrder() {
        return allowMultipleActivePerOrder;
    }

    public void setAllowMultipleActivePerOrder(final boolean allowMultipleActivePerOrder) {
        this.allowMultipleActivePerOrder = allowMultipleActivePerOrder;
    }

    public QrConfig getQr() {
        return qr;
    }

    public void setQr(final QrConfig qr) {
        this.qr = qr;
    }

    public DevConfig getDev() {
        return dev;
    }

    public void setDev(final DevConfig dev) {
        this.dev = dev;
    }

    public IdempotencyConfig getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(final IdempotencyConfig idempotency) {
        this.idempotency = idempotency;
    }

    public OutboxConfig getOutbox() {
        return outbox;
    }

    public void setOutbox(final OutboxConfig outbox) {
        this.outbox = outbox;
    }

    public EventsConfig getEvents() {
        return events;
    }

    public void setEvents(final EventsConfig events) {
        this.events = events;
    }

    public BusinessProcessConfig getBusinessProcess() {
        return businessProcess;
    }

    public void setBusinessProcess(final BusinessProcessConfig businessProcess) {
        this.businessProcess = businessProcess;
    }

    public ExpiryConfig getExpiry() {
        return expiry;
    }

    public void setExpiry(final ExpiryConfig expiry) {
        this.expiry = expiry;
    }

    public VerificationConfig getVerification() {
        return verification;
    }

    public void setVerification(final VerificationConfig verification) {
        this.verification = verification;
    }

    public static class CurrencyConfig {
        private String defaultCurrency = "INR";
        private List<String> allowed = new ArrayList<>(List.of("INR"));

        public String getDefault() {
            return defaultCurrency;
        }

        public void setDefault(final String defaultCurrency) {
            this.defaultCurrency = defaultCurrency;
        }

        public List<String> getAllowed() {
            return allowed;
        }

        public void setAllowed(final List<String> allowed) {
            this.allowed = allowed;
        }
    }

    public static class QrConfig {
        private Duration expiry = Duration.ofMinutes(15);

        public Duration getExpiry() {
            return expiry;
        }

        public void setExpiry(final Duration expiry) {
            this.expiry = expiry;
        }
    }

    public static class DevConfig {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class IdempotencyConfig {
        private Duration ttl = Duration.ofHours(72);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(final Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class OutboxConfig {
        private Duration pollInterval = Duration.ofSeconds(2);
        private int batchSize = 50;
        private Duration lease = Duration.ofSeconds(30);
        private Duration retryInterval = Duration.ofSeconds(10);
        private double backoffMultiplier = 2.0;
        private Duration maxInterval = Duration.ofMinutes(10);
        private int maxRetries = 10;

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(final Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(final int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(final Duration lease) {
            this.lease = lease;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(final Duration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(final double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(final Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(final int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class EventsConfig {
        private DeliveryMode deliveryMode = DeliveryMode.IN_PROCESS;
        private String callbackUrl = "";
        private String callbackSecret = "";
        private Duration callbackTimeout = Duration.ofSeconds(5);

        public enum DeliveryMode {
            IN_PROCESS,
            HTTP_CALLBACK
        }

        public DeliveryMode getDeliveryMode() {
            return deliveryMode;
        }

        public void setDeliveryMode(final DeliveryMode deliveryMode) {
            this.deliveryMode = deliveryMode;
        }

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(final String callbackUrl) {
            this.callbackUrl = callbackUrl;
        }

        public String getCallbackSecret() {
            return callbackSecret;
        }

        public void setCallbackSecret(final String callbackSecret) {
            this.callbackSecret = callbackSecret;
        }

        public Duration getCallbackTimeout() {
            return callbackTimeout;
        }

        public void setCallbackTimeout(final Duration callbackTimeout) {
            this.callbackTimeout = callbackTimeout;
        }
    }

    public static class BusinessProcessConfig {
        private Duration pollInterval = Duration.ofSeconds(5);
        private int maxRetries = 5;
        private double backoffMultiplier = 2.0;

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(final Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(final int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(final double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }
    }

    public static class ExpiryConfig {
        private Duration interval = Duration.ofSeconds(60);
        private Duration grace = Duration.ofSeconds(60);
        private int batchSize = 50;

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(final Duration interval) {
            this.interval = interval;
        }

        public Duration getGrace() {
            return grace;
        }

        public void setGrace(final Duration grace) {
            this.grace = grace;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(final int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class VerificationConfig {
        private Duration interval = Duration.ofSeconds(30);
        private Duration minAge = Duration.ofMinutes(1);
        private int maxAttempts = 10;
        private Duration maxPeriod = Duration.ofMinutes(30);
        private int batchSize = 50;
        private List<Duration> backoff = List.of(
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10)
        );

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(final Duration interval) {
            this.interval = interval;
        }

        public Duration getMinAge() {
            return minAge;
        }

        public void setMinAge(final Duration minAge) {
            this.minAge = minAge;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(final int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getMaxPeriod() {
            return maxPeriod;
        }

        public void setMaxPeriod(final Duration maxPeriod) {
            this.maxPeriod = maxPeriod;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(final int batchSize) {
            this.batchSize = batchSize;
        }

        public List<Duration> getBackoff() {
            return backoff;
        }

        public void setBackoff(final List<Duration> backoff) {
            this.backoff = backoff;
        }
    }
}
