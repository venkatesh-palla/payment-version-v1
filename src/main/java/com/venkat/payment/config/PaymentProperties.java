package com.venkat.payment.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated root configuration properties for the payment service.
 */
@Validated
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    @NotBlank
    private String gateway = "fake";

    @NotNull
    private CurrencyConfig currency = new CurrencyConfig();

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal maxAmount = new BigDecimal("100000.00");

    private boolean allowMultipleActivePerOrder = false;

    @NotNull
    private QrConfig qr = new QrConfig();

    @NotNull
    private WebhookConfig webhook = new WebhookConfig();

    @NotNull
    private VerificationConfig verification = new VerificationConfig();

    @NotNull
    private ExpiryConfig expiry = new ExpiryConfig();

    @NotNull
    private RecoveryConfig recovery = new RecoveryConfig();

    @NotNull
    private OutboxConfig outbox = new OutboxConfig();

    @NotNull
    private EventsConfig events = new EventsConfig();

    @NotNull
    private BusinessProcessConfig businessProcess = new BusinessProcessConfig();

    @NotNull
    private IdempotencyConfig idempotency = new IdempotencyConfig();

    @NotNull
    private ReconciliationConfig reconciliation = new ReconciliationConfig();

    @NotNull
    private DevConfig dev = new DevConfig();

    public String getGateway() {
        return gateway;
    }

    public void setGateway(final String gateway) {
        this.gateway = gateway;
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

    public WebhookConfig getWebhook() {
        return webhook;
    }

    public void setWebhook(final WebhookConfig webhook) {
        this.webhook = webhook;
    }

    public VerificationConfig getVerification() {
        return verification;
    }

    public void setVerification(final VerificationConfig verification) {
        this.verification = verification;
    }

    public ExpiryConfig getExpiry() {
        return expiry;
    }

    public void setExpiry(final ExpiryConfig expiry) {
        this.expiry = expiry;
    }

    public RecoveryConfig getRecovery() {
        return recovery;
    }

    public void setRecovery(final RecoveryConfig recovery) {
        this.recovery = recovery;
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

    public IdempotencyConfig getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(final IdempotencyConfig idempotency) {
        this.idempotency = idempotency;
    }

    public ReconciliationConfig getReconciliation() {
        return reconciliation;
    }

    public void setReconciliation(final ReconciliationConfig reconciliation) {
        this.reconciliation = reconciliation;
    }

    public DevConfig getDev() {
        return dev;
    }

    public void setDev(final DevConfig dev) {
        this.dev = dev;
    }

    public static class CurrencyConfig {
        private String defaultValue = "INR";
        private List<String> allowed = List.of("INR");

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(final String defaultValue) {
            this.defaultValue = defaultValue;
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

    public static class WebhookConfig {
        private boolean enabled = true;
        private List<String> allowedIps = new ArrayList<>();
        private int maxBodyBytes = 262144;
        private Duration timestampTolerance = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedIps() {
            return allowedIps;
        }

        public void setAllowedIps(final List<String> allowedIps) {
            this.allowedIps = allowedIps;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(final int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public Duration getTimestampTolerance() {
            return timestampTolerance;
        }

        public void setTimestampTolerance(final Duration timestampTolerance) {
            this.timestampTolerance = timestampTolerance;
        }
    }

    public static class VerificationConfig {
        private boolean enabled = true;
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

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

    public static class RecoveryConfig {
        private Duration interval = Duration.ofMinutes(5);
        private Duration stuckThreshold = Duration.ofMinutes(5);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(final Duration interval) {
            this.interval = interval;
        }

        public Duration getStuckThreshold() {
            return stuckThreshold;
        }

        public void setStuckThreshold(final Duration stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
        }
    }

    public static class OutboxConfig {
        private Duration pollInterval = Duration.ofSeconds(2);
        private int batchSize = 50;
        private Duration lease = Duration.ofSeconds(30);
        private RetryConfig retry = new RetryConfig();
        private int maxRetries = 10;
        private Period retention = Period.ofDays(7);

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

        public RetryConfig getRetry() {
            return retry;
        }

        public void setRetry(final RetryConfig retry) {
            this.retry = retry;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(final int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Period getRetention() {
            return retention;
        }

        public void setRetention(final Period retention) {
            this.retention = retention;
        }
    }

    public static class RetryConfig {
        private Duration interval = Duration.ofSeconds(10);
        private double backoffMultiplier = 2.0;
        private Duration maxInterval = Duration.ofMinutes(10);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(final Duration interval) {
            this.interval = interval;
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
    }

    public static class EventsConfig {
        private String deliveryMode = "IN_PROCESS";
        private String callbackUrl = "";
        private String callbackSecret = "";
        private Duration callbackTimeout = Duration.ofSeconds(5);

        public String getDeliveryMode() {
            return deliveryMode;
        }

        public void setDeliveryMode(final String deliveryMode) {
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

    public static class IdempotencyConfig {
        private Duration ttl = Duration.ofHours(72);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(final Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class ReconciliationConfig {
        private boolean enabled = false;
        private String cron = "0 30 2 * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(final String cron) {
            this.cron = cron;
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
}

