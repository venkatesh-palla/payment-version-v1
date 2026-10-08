package com.venkat.payment.dev;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup sanity check and notification for active developer endpoints.
 */
@Component
@Profile("!prod & !production")
@ConditionalOnProperty(prefix = "payment.dev", name = "enabled", havingValue = "true")
public class DevStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(DevStartupLogger.class);

    private final Environment environment;

    public DevStartupLogger(final Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void warnDevEndpointsActive() {
        final Set<String> activeProfiles = Arrays.stream(this.environment.getActiveProfiles())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (activeProfiles.contains("prod") || activeProfiles.contains("production")) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Dev endpoints must never be enabled in prod profile!");
        }

        log.warn("********************************************************************************");
        log.warn("** WARNING: DEV ENDPOINTS & SIMULATOR ARE CURRENTLY ENABLED!                  **");
        log.warn("** Available: GET  /dev/pay-demo                                              **");
        log.warn("**            POST /dev/fake-gateway/{paymentReference}/simulate              **");
        log.warn("**            GET  /dev/qr/{paymentReference}                                 **");
        log.warn("** NEVER ENABLE THIS IN PRODUCTION ENVIRONMENTS!                              **");
        log.warn("********************************************************************************");
    }
}

