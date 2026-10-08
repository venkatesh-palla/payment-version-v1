package com.venkat.payment.gateway.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies Razorpay HMAC-SHA256 webhook signatures and provides timestamp replay protection.
 */
@Component
public class RazorpaySignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(RazorpaySignatureVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final RazorpayProperties properties;
    private final Clock clock;

    public RazorpaySignatureVerifier(final RazorpayProperties properties, final Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Verifies the HMAC-SHA256 signature of the raw request payload against the secret.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param rawBody   raw unparsed request payload
     * @param signature hex signature from X-Razorpay-Signature header
     * @return true if valid and authentic
     */
    public boolean verifySignature(final byte[] rawBody, final String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }

        final String secret = this.properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("Razorpay webhook secret is not configured; rejecting webhook");
            return false;
        }

        try {
            final Mac mac = Mac.getInstance(HMAC_SHA256);
            final SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKey);
            final byte[] calculatedHmac = mac.doFinal(rawBody);

            final StringBuilder hexString = new StringBuilder();
            for (final byte b : calculatedHmac) {
                final String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            final String expectedSignature = hexString.toString();
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (final Exception ex) {
            log.error("Error verifying Razorpay webhook signature", ex);
            return false;
        }
    }

    /**
     * Replay protection validating timestamp tolerance if provider supplies an event timestamp.
     * // INTEGRATION POINT: verify in provider docs whether Razorpay webhook delivers timestamp in header or payload
     *
     * @param eventTimestampEpochSeconds epoch timestamp in seconds from provider event
     * @return true if within acceptable clock skew / tolerance window
     */
    public boolean verifyTimestamp(final Long eventTimestampEpochSeconds) {
        if (eventTimestampEpochSeconds == null) {
            // If provider does not send a timestamp header, fallback to accepting with warning
            // INTEGRATION POINT: verify in provider docs
            log.debug("No timestamp provided in Razorpay webhook for replay verification");
            return true;
        }

        final Instant eventTime = Instant.ofEpochSecond(eventTimestampEpochSeconds);
        final Instant now = this.clock.instant();
        final Duration tolerance = this.properties.getReplayTolerance();

        final Duration difference = Duration.between(eventTime, now).abs();
        final boolean withinTolerance = difference.compareTo(tolerance) <= 0;

        if (!withinTolerance) {
            log.warn("Razorpay webhook timestamp [{}] exceeds replay tolerance window of {} seconds (skew: {}s)",
                    eventTime, tolerance.getSeconds(), difference.getSeconds());
        }

        return withinTolerance;
    }
}

