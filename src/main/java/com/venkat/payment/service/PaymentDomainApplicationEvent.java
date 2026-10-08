package com.venkat.payment.service;

import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import org.springframework.context.ApplicationEvent;

/**
 * In-process Spring application event carrying an outbox record to downstream handlers.
 */
public class PaymentDomainApplicationEvent extends ApplicationEvent {

    private final OutboxRecord outboxRecord;

    public PaymentDomainApplicationEvent(final Object source, final OutboxRecord outboxRecord) {
        super(source);
        this.outboxRecord = outboxRecord;
    }

    public OutboxRecord getOutboxRecord() {
        return outboxRecord;
    }
}

