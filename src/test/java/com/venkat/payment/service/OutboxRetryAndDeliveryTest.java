package com.venkat.payment.service;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.repository.PaymentOutboxRepository;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OutboxRetryAndDeliveryTest {

    private static final String CALLBACK_URL = "http://internal-erp-system:8080/api/events/webhook";

    @Mock
    private PaymentOutboxRepository outboxRepository;

    private PaymentProperties paymentProperties;
    private Clock fixedClock;
    private MockRestServiceServer mockServer;
    private PaymentOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        this.paymentProperties = new PaymentProperties();
        this.paymentProperties.getEvents().setCallbackUrl(CALLBACK_URL);
        this.paymentProperties.getEvents().setCallbackSecret("test-signing-secret-12345");

        final RestClient.Builder restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        final HttpCallbackEventPublisher httpPublisher = new HttpCallbackEventPublisher(
                this.paymentProperties,
                this.fixedClock,
                restClientBuilder
        );
        this.dispatcher = new PaymentOutboxDispatcher(this.outboxRepository, httpPublisher, this.paymentProperties, this.fixedClock);
    }

    @Test
    @DisplayName("Downstream HTTP callback down -> Outbox retries -> Downstream comes up -> Event delivered, outbox status DELIVERED")
    void outboxRetriesOnDownstreamFailureAndDeliversWhenRestored() {
        final UUID outboxId = UUID.randomUUID();
        final UUID paymentId = UUID.randomUUID();
        final OutboxRecord initialEvent = new OutboxRecord(
                outboxId, 1L, "EVT-OUTBOX-101", paymentId, "PAYMENT_SUCCESS",
                "{\"paymentId\":\"" + paymentId + "\",\"status\":\"SUCCESS\"}",
                "PENDING", 0, this.fixedClock.instant(), null, this.fixedClock.instant(), null
        );

        // 1. Simulate Downstream service failure (HTTP 500)
        this.mockServer.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        this.dispatcher.dispatchSingleEvent(initialEvent);

        // Verify outbox was updated with incremented retryCount and PENDING status
        final ArgumentCaptor<Instant> nextRetryCaptor = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        verify(this.outboxRepository).recordDeliveryFailure(
                eq(outboxId),
                eq(1),
                nextRetryCaptor.capture(),
                errorCaptor.capture(),
                eq(false)
        );
        verify(this.outboxRepository, never()).markDelivered(any());
        assertThat(errorCaptor.getValue()).contains("500");
        assertThat(nextRetryCaptor.getValue()).isAfter(this.fixedClock.instant());

        this.mockServer.verify();
        this.mockServer.reset();

        // 2. Downstream service recovered and is now UP (HTTP 200)
        this.mockServer.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("X-Timestamp", this.fixedClock.instant().toString()))
                .andExpect(content().string(initialEvent.payload()))
                .andRespond(withSuccess("{\"acknowledged\":true}", MediaType.APPLICATION_JSON));

        final OutboxRecord retryEvent = new OutboxRecord(
                outboxId, 1L, "EVT-OUTBOX-101", paymentId, "PAYMENT_SUCCESS",
                initialEvent.payload(), "PENDING", 1, nextRetryCaptor.getValue(), "HTTP 500", initialEvent.createdAt(), null
        );

        this.dispatcher.dispatchSingleEvent(retryEvent);

        // Verify outbox is now marked DELIVERED
        verify(this.outboxRepository).markDelivered(eq(outboxId));
        this.mockServer.verify();
    }
}

