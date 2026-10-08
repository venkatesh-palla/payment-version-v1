package com.venkat.payment.security;

import com.venkat.payment.config.PaymentProperties;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration enforcing OAuth2 Resource Server JWT authentication,
 * scope checks, and public access for actuator, dev, and webhook endpoints.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final PaymentProperties paymentProperties;
    private final Environment environment;

    public SecurityConfig(final PaymentProperties paymentProperties, final Environment environment) {
        this.paymentProperties = paymentProperties;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/dev/**").permitAll()
                        .requestMatchers("/api/v1/payments/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments").hasAnyAuthority("SCOPE_payments:write", "SCOPE_payments:create", "SCOPE_payments:internal")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/cancel").hasAnyAuthority("SCOPE_payments:write", "SCOPE_payments:cancel", "SCOPE_payments:internal")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/refund").hasAuthority("SCOPE_payments:internal")
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/**").hasAnyAuthority("SCOPE_payments:read", "SCOPE_payments:internal")
                        .requestMatchers("/api/v1/internal/**").hasAuthority("SCOPE_payments:internal")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        final Set<String> activeProfiles = Arrays.stream(this.environment.getActiveProfiles())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        final boolean isProd = activeProfiles.contains("prod") || activeProfiles.contains("production");
        if (isProd) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Dev HS256 JWT decoder cannot be used in prod profile!");
        }

        final String secret = this.paymentProperties.getDevJwtSecret();
        final SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }
}

