package com.venkat.payment.config;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup safety guard enforcing critical security boundaries across runtime profiles.
 * Fails fast if unsafe combinations of development, fake, or live-smoke configurations are detected.
 */
@Component
public class ProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(ProfileGuard.class);
    private static final BigDecimal LIVE_SMOKE_MAX_ALLOWED_AMOUNT = new BigDecimal("10.00");

    private final Environment environment;
    private final PaymentProperties paymentProperties;

    public ProfileGuard(final Environment environment, final PaymentProperties paymentProperties) {
        this.environment = environment;
        this.paymentProperties = paymentProperties;
    }

    @PostConstruct
    public void validateProfileSafety() {
        final Set<String> activeProfiles = Arrays.stream(this.environment.getActiveProfiles())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        log.info("Validating profile safety for active profiles: {}", activeProfiles);

        final boolean isProd = activeProfiles.contains("prod") || activeProfiles.contains("production");
        final boolean isLiveSmoke = activeProfiles.contains("live-smoke");

        if (isProd) {
            enforceProductionSafety();
        }

        if (isLiveSmoke) {
            enforceLiveSmokeSafety();
        }
    }

    private void enforceProductionSafety() {
        if ("fake".equalsIgnoreCase(this.paymentProperties.getGateway())) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Fake gateway cannot be enabled in prod profile!");
        }

        if (this.paymentProperties.getDev().isEnabled()) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Dev features/endpoints cannot be enabled in prod profile!");
        }

        final String devJwtSecret = this.environment.getProperty("DEV_JWT_SECRET");
        if (devJwtSecret != null && !devJwtSecret.isBlank()) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Dev JWT secret cannot be configured in prod profile!");
        }
    }

    private void enforceLiveSmokeSafety() {
        if (this.paymentProperties.getMaxAmount().compareTo(LIVE_SMOKE_MAX_ALLOWED_AMOUNT) > 0) {
            throw new IllegalStateException(
                    "CRITICAL SAFETY ERROR: 'live-smoke' profile max-amount cannot exceed "
                            + LIVE_SMOKE_MAX_ALLOWED_AMOUNT
                            + " but was configured as "
                            + this.paymentProperties.getMaxAmount()
            );
        }
    }
}

