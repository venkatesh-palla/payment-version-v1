package com.venkat.payment.gateway.razorpay;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client communicating with Razorpay REST APIs.
 * Equipped with connect/read timeouts, Resilience4j circuit breaker, and retry for idempotent calls.
 * Strictly avoids logging authentication credentials and sensitive request headers.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final RazorpayProperties properties;
    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry idempotentRetry;

    @Autowired
    public RazorpayClient(final RazorpayProperties properties) {
        this(properties, createDefaultBuilder(properties));
    }

    public RazorpayClient(final RazorpayProperties properties, final RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();

        final CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.circuitBreaker = CircuitBreaker.of("razorpayCircuitBreaker", cbConfig);

        final RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(300))
                .retryExceptions(Exception.class)
                .build();
        this.idempotentRetry = Retry.of("razorpayIdempotentRetry", retryConfig);
    }

    private static RestClient.Builder createDefaultBuilder(final RazorpayProperties properties) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        final int timeoutMs = (int) properties.getTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        return RestClient.builder().requestFactory(factory);
    }

    private String getBasicAuthHeader() {
        final String credentials = (properties.getKeyId() != null ? properties.getKeyId() : "") + ":" +
                (properties.getKeySecret() != null ? properties.getKeySecret() : "");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a dynamic UPI QR code. Non-idempotent create call wrapped in circuit breaker.
     */
    public RazorpayQrResponse createQrCode(final RazorpayCreateQrRequest request) {
        final String url = properties.getBaseUrl() + "/payments/qr_codes";
        return circuitBreaker.executeSupplier(() ->
                this.restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, getBasicAuthHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(RazorpayQrResponse.class)
        );
    }

    /**
     * Fetches details of an existing QR code. Safe/idempotent query protected by retry and circuit breaker.
     */
    public RazorpayQrResponse getQrCode(final String qrId) {
        final String url = properties.getBaseUrl() + "/payments/qr_codes/" + qrId;
        return circuitBreaker.executeSupplier(() ->
                Retry.decorateSupplier(this.idempotentRetry, () ->
                        this.restClient.get()
                                .uri(url)
                                .header(HttpHeaders.AUTHORIZATION, getBasicAuthHeader())
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .body(RazorpayQrResponse.class)
                ).get()
        );
    }

    /**
     * Lists payments received against a QR code. Safe/idempotent query protected by retry and circuit breaker.
     */
    public RazorpayPaymentListResponse getPaymentsForQr(final String qrId) {
        final String url = properties.getBaseUrl() + "/payments/qr_codes/" + qrId + "/payments";
        return circuitBreaker.executeSupplier(() ->
                Retry.decorateSupplier(this.idempotentRetry, () ->
                        this.restClient.get()
                                .uri(url)
                                .header(HttpHeaders.AUTHORIZATION, getBasicAuthHeader())
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .body(RazorpayPaymentListResponse.class)
                ).get()
        );
    }

    /**
     * Closes an active QR code so it cannot be scanned after expiry or cancellation.
     * Idempotent call protected by retry and circuit breaker.
     */
    public void closeQrCode(final String qrId) {
        final String url = properties.getBaseUrl() + "/payments/qr_codes/" + qrId + "/close";
        circuitBreaker.executeRunnable(() ->
                Retry.decorateRunnable(this.idempotentRetry, () ->
                        this.restClient.post()
                                .uri(url)
                                .header(HttpHeaders.AUTHORIZATION, getBasicAuthHeader())
                                .retrieve()
                                .toBodilessEntity()
                ).run()
        );
    }

    /**
     * Issues a refund against a captured payment. Protected by circuit breaker.
     */
    public RazorpayRefundResponse createRefund(final String paymentId, final RazorpayRefundRequest request) {
        final String url = properties.getBaseUrl() + "/payments/" + paymentId + "/refund";
        return circuitBreaker.executeSupplier(() ->
                this.restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, getBasicAuthHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(RazorpayRefundResponse.class)
        );
    }
}

