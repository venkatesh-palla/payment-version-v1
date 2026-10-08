package com.venkat.payment.service;

import com.venkat.payment.repository.PaymentOutboxRepository;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service providing internal event queries and operational requeuing actions.
 */
@Service
public class InternalEventsService {

    private final PaymentOutboxRepository outboxRepository;

    public InternalEventsService(final PaymentOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public List<OutboxRecord> getEventsAfter(final long afterSequenceNo, final int limit) {
        return this.outboxRepository.findEventsAfter(afterSequenceNo, limit);
    }

    public int requeueFailedEvents() {
        return this.outboxRepository.requeueFailedEvents();
    }
}

