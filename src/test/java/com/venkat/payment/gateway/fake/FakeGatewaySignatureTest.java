package com.venkat.payment.gateway.fake;

import com.venkat.payment.config.PaymentProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeGatewaySignatureTest {

    private FakeGateway fakeGateway;
    private PaymentProperties properties;
    private static final String SECRET = "test-webhook-secret-key-12345";

    @BeforeEach
    void setUp() {
        this.properties = new PaymentProperties();
        this.properties.setFakeGatewayWebhookSecret(SECRET);
        this.fakeGateway = new FakeGateway(this.properties);
    }

    @Test
    @DisplayName("Valid HMAC-SHA256 signature should pass verification")
    void validSignaturePasses() {
        final byte[] rawBody = "{\"eventId\":\"evt_101\",\"amount\":\"500.00\"}".getBytes(StandardCharsets.UTF_8);
        final String validSig = FakeGateway.calculateHmacSha256(rawBody, SECRET);

        assertThat(this.fakeGateway.verifyWebhookSignature(rawBody, validSig)).isTrue();
    }

    @Test
    @DisplayName("Tampered body payload should fail verification")
    void tamperedBodyFails() {
        final byte[] rawBody = "{\"eventId\":\"evt_101\",\"amount\":\"500.00\"}".getBytes(StandardCharsets.UTF_8);
        final String validSig = FakeGateway.calculateHmacSha256(rawBody, SECRET);

        final byte[] tamperedBody = "{\"eventId\":\"evt_101\",\"amount\":\"5000.00\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(this.fakeGateway.verifyWebhookSignature(tamperedBody, validSig)).isFalse();
    }

    @Test
    @DisplayName("Signature generated with wrong secret should fail verification")
    void wrongSecretFails() {
        final byte[] rawBody = "{\"eventId\":\"evt_101\",\"amount\":\"500.00\"}".getBytes(StandardCharsets.UTF_8);
        final String wrongSig = FakeGateway.calculateHmacSha256(rawBody, "wrong-secret-key");

        assertThat(this.fakeGateway.verifyWebhookSignature(rawBody, wrongSig)).isFalse();
    }

    @Test
    @DisplayName("Null or empty signatures should fail verification safely")
    void nullOrEmptySignatureFails() {
        final byte[] rawBody = "{\"eventId\":\"evt_101\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(this.fakeGateway.verifyWebhookSignature(rawBody, null)).isFalse();
        assertThat(this.fakeGateway.verifyWebhookSignature(rawBody, "")).isFalse();
        assertThat(this.fakeGateway.verifyWebhookSignature(rawBody, "   ")).isFalse();
        assertThat(this.fakeGateway.verifyWebhookSignature(null, "some-sig")).isFalse();
    }
}

