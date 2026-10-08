package com.venkat.payment.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.gateway.fake.FakeGateway;
import com.venkat.payment.service.DownstreamEventHandler;
import com.venkat.payment.service.OutboxEventPayload;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fake downstream receiver controller for testing HTTP_CALLBACK mode.
 */
@RestController
@RequestMapping("/dev/fake-downstream")
@Profile("!prod & !production")
@ConditionalOnProperty(prefix = "payment.dev", name = "enabled", havingValue = "true")
public class FakeDownstreamReceiverController {

    private static final Logger log = LoggerFactory.getLogger(FakeDownstreamReceiverController.class);

    private final DownstreamEventHandler downstreamEventHandler;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;

    public FakeDownstreamReceiverController(final DownstreamEventHandler downstreamEventHandler,
                                            final PaymentProperties paymentProperties,
                                            final ObjectMapper objectMapper) {
        this.downstreamEventHandler = downstreamEventHandler;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, String>> receiveCallback(
            @RequestHeader(value = "X-Event-Id", required = false) final String eventId,
            @RequestHeader(value = "X-Signature", required = false) final String signature,
            @RequestBody final byte[] rawPayload) {

        final String secret = this.paymentProperties.getEvents().getCallbackSecret();
        final String expectedSig = FakeGateway.calculateHmacSha256(rawPayload, secret != null ? secret : "default-secret");

        if (signature == null || !signature.equalsIgnoreCase(expectedSig)) {
            log.warn("Fake downstream rejected invalid signature on event [{}]", eventId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid signature"));
        }

        try {
            final OutboxEventPayload payload = this.objectMapper.readValue(rawPayload, OutboxEventPayload.class);
            this.downstreamEventHandler.handleRawEvent(payload.eventId(), payload.eventType(), payload.paymentId());
            return ResponseEntity.ok(Map.of("status", "received", "eventId", eventId));
        } catch (final Exception ex) {
            log.error("Fake downstream failed to parse event", ex);
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed payload"));
        }
    }
}

