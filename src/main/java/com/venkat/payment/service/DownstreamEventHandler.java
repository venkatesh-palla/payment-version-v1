package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.repository.BusinessProcessRepository;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Downstream consumer handler receiving payment domain events and initiating asynchronous business processes.
 * Ensures strict deduplication using consumer_processed_events table.
 */
@Service
public class DownstreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DownstreamEventHandler.class);
    private static final String CONSUMER_NAME = "DownstreamOrderFulfillmentConsumer";

    private final BusinessProcessRepository businessProcessRepository;
    private final ObjectMapper objectMapper;

    public DownstreamEventHandler(final BusinessProcessRepository businessProcessRepository,
                                  final ObjectMapper objectMapper) {
        this.businessProcessRepository = businessProcessRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Transactional
    public void handleInProcessEvent(final PaymentDomainApplicationEvent applicationEvent) {
        handleEvent(applicationEvent.getOutboxRecord());
    }

    @Transactional
    public boolean handleEvent(final OutboxRecord outboxRecord) {
        final String eventId = outboxRecord.eventId();

        // 1. Deduplication check via atomic insert
        final boolean inserted = this.businessProcessRepository.insertConsumerProcessedEvent(eventId, CONSUMER_NAME);
        if (!inserted) {
            log.info("Consumer [{}] detected duplicate event [{}]. Skipped.", CONSUMER_NAME, eventId);
            return false;
        }

        // 2. Map event to downstream process type
        final String processType = switch (outboxRecord.eventType()) {
            case "PAYMENT_SUCCESS" -> "ORDER_FULFILLMENT";
            case "PAYMENT_FAILED", "PAYMENT_EXPIRED" -> "RELEASE_RESOURCES";
            default -> null;
        };

        if (processType != null) {
            this.businessProcessRepository.insertBusinessProcess(outboxRecord.paymentId(), processType);
            log.info("Initiated business process [{}] for payment [{}] via event [{}]",
                    processType, outboxRecord.paymentId(), eventId);
        }

        return true;
    }

    @Transactional
    public boolean handleRawEvent(final String eventId, final String eventType, final UUID paymentId) {
        final boolean inserted = this.businessProcessRepository.insertConsumerProcessedEvent(eventId, CONSUMER_NAME);
        if (!inserted) {
            log.info("Consumer [{}] duplicate raw event [{}]. Skipped.", CONSUMER_NAME, eventId);
            return false;
        }

        final String processType = switch (eventType) {
            case "PAYMENT_SUCCESS" -> "ORDER_FULFILLMENT";
            case "PAYMENT_FAILED", "PAYMENT_EXPIRED" -> "RELEASE_RESOURCES";
            default -> null;
        };

        if (processType != null) {
            this.businessProcessRepository.insertBusinessProcess(paymentId, processType);
        }
        return true;
    }
}

