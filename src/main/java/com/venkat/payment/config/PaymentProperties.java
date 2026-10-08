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
 * Validated configuration properties for the payment service.
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
}
