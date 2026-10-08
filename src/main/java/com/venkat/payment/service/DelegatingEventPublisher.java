package com.venkat.payment.service;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Primary delegating publisher selecting the delivery mechanism based on payment.events.delivery-mode.
 */
@Component
@Primary
public class DelegatingEventPublisher implements EventPublisher {

    private final InProcessEventPublisher inProcessPublisher;
    private final HttpCallbackEventPublisher httpCallbackPublisher;
    private final PaymentProperties paymentProperties;

    public DelegatingEventPublisher(final InProcessEventPublisher inProcessPublisher,
                                    final HttpCallbackEventPublisher httpCallbackPublisher,
                                    final PaymentProperties paymentProperties) {
        this.inProcessPublisher = inProcessPublisher;
        this.httpCallbackPublisher = httpCallbackPublisher;
        this.paymentProperties = paymentProperties;
    }

    @Override
    public DeliveryResult publish(final OutboxRecord event) {
        if (this.paymentProperties.getEvents().getDeliveryMode() == PaymentProperties.EventsConfig.DeliveryMode.HTTP_CALLBACK) {
            return this.httpCallbackPublisher.publish(event);
        }
        return this.inProcessPublisher.publish(event);
    }
}

