package com.venkat.payment.dev;

import com.venkat.payment.security.JwtTokenService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer endpoint allowing on-the-fly minting of JWTs for local testing.
 */
@RestController
@RequestMapping("/dev")
@Profile("!prod & !production")
@ConditionalOnProperty(prefix = "payment.dev", name = "enabled", havingValue = "true")
public class DevTokenController {

    private final JwtTokenService jwtTokenService;

    public DevTokenController(final JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> generateToken(
            @RequestParam(name = "sub", defaultValue = "customer-1") final String subject,
            @RequestParam(name = "scopes", defaultValue = "payments:create payments:read") final String scopes,
            @RequestParam(name = "ttl", defaultValue = "3600") final long ttlSeconds) {

        final List<String> scopeList = Arrays.stream(scopes.split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .toList();

        final String token = this.jwtTokenService.generateToken(subject, scopeList, ttlSeconds);

        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", ttlSeconds,
                "scope", String.join(" ", scopeList),
                "sub", subject
        ));
    }
}

