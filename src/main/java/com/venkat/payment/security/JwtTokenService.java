package com.venkat.payment.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.venkat.payment.config.PaymentProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service to generate and validate HS256 JWT tokens for local development and testing.
 */
@Service
public class JwtTokenService {

    private final PaymentProperties paymentProperties;

    public JwtTokenService(final PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    public String generateToken(final String subject, final List<String> scopes, final long ttlSeconds) {
        try {
            final String secret = this.paymentProperties.getDevJwtSecret();
            final JWSSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));

            final Instant now = Instant.now();
            final JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer("payment-service-local")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .claim("scope", String.join(" ", scopes))
                    .build();

            final SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate JWT token", e);
        }
    }
}

