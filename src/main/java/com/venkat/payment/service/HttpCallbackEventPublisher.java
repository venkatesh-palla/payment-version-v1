package com.venkat.payment.service;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.gateway.fake.FakeGateway;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Event publisher delivering events via signed HTTP callbacks to external downstream endpoints.
 */
@Component
public class HttpCallbackEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpCallbackEventPublisher.class);

    private final PaymentProperties paymentProperties;
    private final RestClient restClient;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpCallbackEventPublisher(final PaymentProperties paymentProperties, final Clock clock) {
        this(paymentProperties, clock, createDefaultBuilder(paymentProperties));
    }

    public HttpCallbackEventPublisher(final PaymentProperties paymentProperties,
                                      final Clock clock,
                                      final RestClient.Builder restClientBuilder) {
        this.paymentProperties = paymentProperties;
        this.clock = clock;
        this.restClient = restClientBuilder.build();
    }

    private static RestClient.Builder createDefaultBuilder(final PaymentProperties paymentProperties) {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        final int timeoutMs = (int) paymentProperties.getEvents().getCallbackTimeout().toMillis();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Override
    public DeliveryResult publish(final OutboxRecord event) {
        final String callbackUrl = this.paymentProperties.getEvents().getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("Cannot deliver event [{}]: callback-url is not configured", event.eventId());
            return DeliveryResult.failure("Callback URL not configured");
        }

        final String secret = this.paymentProperties.getEvents().getCallbackSecret();
        final byte[] payloadBytes = event.payload().getBytes(StandardCharsets.UTF_8);
        final String signature = FakeGateway.calculateHmacSha256(payloadBytes, secret != null ? secret : "default-secret");
        final String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : UUID.randomUUID().toString();
        final String timestamp = this.clock.instant().toString();

        try {
            final HttpStatusCode statusCode = this.restClient.post()
                    .uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Event-Id", event.eventId())
                    .header("X-Event-Type", event.eventType())
                    .header("X-Correlation-Id", correlationId)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", signature != null ? signature : "")
                    .body(payloadBytes)
                    .exchange((req, res) -> res.getStatusCode());

            if (statusCode.is2xxSuccessful()) {
                log.info("Successfully delivered event [{}] to callback URL [{}], status: {}",
                        event.eventId(), callbackUrl, statusCode);
                return DeliveryResult.ok();
            } else {
                final String err = "HTTP callback responded with non-2xx status: " + statusCode;
                log.warn("Failed delivering event [{}]: {}", event.eventId(), err);
                return DeliveryResult.failure(err);
            }
        } catch (final Exception ex) {
            log.error("Network error delivering event [{}] to callback URL [{}]", event.eventId(), callbackUrl, ex);
            return DeliveryResult.failure("Network error: " + ex.getMessage());
        }
    }
}
