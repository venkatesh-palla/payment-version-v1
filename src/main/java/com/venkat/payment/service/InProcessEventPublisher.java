package com.venkat.payment.service;

import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process publisher dispatching events via Spring ApplicationEventPublisher to local consumers.
 */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;

    public InProcessEventPublisher(final ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public DeliveryResult publish(final OutboxRecord event) {
        try {
            log.info("Publishing in-process event [{}] for payment [{}]", event.eventId(), event.paymentId());
            this.applicationEventPublisher.publishEvent(new PaymentDomainApplicationEvent(this, event));
            return DeliveryResult.ok();
        } catch (final Exception ex) {
            log.error("Failed to publish in-process event [{}]", event.eventId(), ex);
            return DeliveryResult.failure(ex.getMessage());
        }
    }
}
