package com.venkat.payment.service;

import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;

/**
 * Pluggable event publisher abstraction for outbox event delivery.
 * Extension point for future message broker adapters (e.g. Kafka, RabbitMQ) if ever adopted.
 */
public interface EventPublisher {

    /**
     * Publishes an outbox domain event to its target consumer.
     * Implementation must be idempotent.
     *
     * @param event outbox event record
     * @return delivery result with success status and optional error details
     */
    DeliveryResult publish(OutboxRecord event);

    record DeliveryResult(boolean success, String errorMessage) {
        public static DeliveryResult ok() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult failure(final String error) {
            return new DeliveryResult(false, error);
        }
    }
}
