package com.venkat.payment.config;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProfileGuardTest {

    private MockEnvironment environment;
    private PaymentProperties paymentProperties;
    private ProfileGuard profileGuard;

    @BeforeEach
    void setUp() {
        this.environment = new MockEnvironment();
        this.paymentProperties = new PaymentProperties();
        this.profileGuard = new ProfileGuard(this.environment, this.paymentProperties);
    }

    @Test
    void shouldPassWhenLocalProfileWithFakeGateway() {
        this.environment.setActiveProfiles("local");
        this.paymentProperties.setGateway("fake");
        this.paymentProperties.getDev().setEnabled(true);

        Assertions.assertDoesNotThrow(() -> this.profileGuard.validateProfileSafety());
    }

    @Test
    void shouldFailWhenProdProfileWithFakeGateway() {
        this.environment.setActiveProfiles("prod");
        this.paymentProperties.setGateway("fake");
        this.paymentProperties.getDev().setEnabled(false);

        final IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> this.profileGuard.validateProfileSafety()
        );
        Assertions.assertTrue(ex.getMessage().contains("Fake gateway cannot be enabled in prod"));
    }

    @Test
    void shouldFailWhenProdProfileWithDevEnabled() {
        this.environment.setActiveProfiles("prod");
        this.paymentProperties.setGateway("razorpay");
        this.paymentProperties.getDev().setEnabled(true);

        final IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> this.profileGuard.validateProfileSafety()
        );
        Assertions.assertTrue(ex.getMessage().contains("Dev features/endpoints cannot be enabled in prod"));
    }

    @Test
    void shouldFailWhenProdProfileWithDevJwtSecret() {
        this.environment.setActiveProfiles("prod");
        this.environment.setProperty("DEV_JWT_SECRET", "insecure-dev-secret-key");
        this.paymentProperties.setGateway("razorpay");
        this.paymentProperties.getDev().setEnabled(false);

        final IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> this.profileGuard.validateProfileSafety()
        );
        Assertions.assertTrue(ex.getMessage().contains("Dev JWT secret cannot be configured in prod"));
    }

    @Test
    void shouldPassWhenProdProfileWithRealGatewayAndDevDisabled() {
        this.environment.setActiveProfiles("prod");
        this.paymentProperties.setGateway("razorpay");
        this.paymentProperties.getDev().setEnabled(false);

        Assertions.assertDoesNotThrow(() -> this.profileGuard.validateProfileSafety());
    }

    @Test
    void shouldFailWhenLiveSmokeWithMaxAmountGreaterThan10() {
        this.environment.setActiveProfiles("live-smoke");
        this.paymentProperties.setMaxAmount(new BigDecimal("10.01"));

        final IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> this.profileGuard.validateProfileSafety()
        );
        Assertions.assertTrue(ex.getMessage().contains("'live-smoke' profile max-amount cannot exceed 10.00"));
    }

    @Test
    void shouldPassWhenLiveSmokeWithMaxAmountLessThanOrEqualTo10() {
        this.environment.setActiveProfiles("live-smoke");
        this.paymentProperties.setMaxAmount(new BigDecimal("10.00"));

        Assertions.assertDoesNotThrow(() -> this.profileGuard.validateProfileSafety());
    }
}

