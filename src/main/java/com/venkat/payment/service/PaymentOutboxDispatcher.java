package com.venkat.payment.service;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.repository.PaymentOutboxRepository;
import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Dispatcher polling pending outbox events using SKIP LOCKED row claims
 * and delivering them reliably with exponential backoff and ShedLock clustering protection.
 */
@Service
public class PaymentOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxDispatcher.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentOutboxRepository outboxRepository;
    private final EventPublisher eventPublisher;
    private final PaymentProperties paymentProperties;
    private final Clock clock;

    public PaymentOutboxDispatcher(final PaymentOutboxRepository outboxRepository,
                                   final EventPublisher eventPublisher,
                                   final PaymentProperties paymentProperties,
                                   final Clock clock) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.paymentProperties = paymentProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval:PT2S}")
    @SchedulerLock(name = "PaymentOutboxDispatcher", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void dispatchPendingEvents() {
        final int batchSize = this.paymentProperties.getOutbox().getBatchSize();
        final Duration lease = this.paymentProperties.getOutbox().getLease();

        final List<OutboxRecord> batch = this.outboxRepository.claimPendingEvents(batchSize, lease);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Claimed {} pending outbox events for dispatch", batch.size());

        for (final OutboxRecord event : batch) {
            dispatchSingleEvent(event);
        }
    }

    public void dispatchSingleEvent(final OutboxRecord event) {
        try {
            // Deliver OUTSIDE database transaction
            final EventPublisher.DeliveryResult result = this.eventPublisher.publish(event);

            if (result.success()) {
                this.outboxRepository.markDelivered(event.id());
                log.info("Outbox event [{}] successfully delivered", event.eventId());
            } else {
                handleDeliveryFailure(event, result.errorMessage());
            }
        } catch (final Exception ex) {
            handleDeliveryFailure(event, ex.getMessage());
        }
    }

    private void handleDeliveryFailure(final OutboxRecord event, final String error) {
        final int nextRetryCount = event.retryCount() + 1;
        final int maxRetries = this.paymentProperties.getOutbox().getMaxRetries();
        final boolean maxRetriesExceeded = nextRetryCount >= maxRetries;

        final Instant now = this.clock.instant();
        final Instant nextRetryAt = calculateNextRetry(now, nextRetryCount);

        this.outboxRepository.recordDeliveryFailure(
                event.id(),
                nextRetryCount,
                nextRetryAt,
                error,
                maxRetriesExceeded
        );

        if (maxRetriesExceeded) {
            log.error("Outbox event [{}] PERMANENTLY FAILED after {} retries. Last error: {}",
                    event.eventId(), nextRetryCount, error);
        } else {
            log.warn("Outbox event [{}] delivery failed (attempt {}/{}). Will retry at {}. Error: {}",
                    event.eventId(), nextRetryCount, maxRetries, nextRetryAt, error);
        }
    }

    private Instant calculateNextRetry(final Instant from, final int retryCount) {
        final Duration base = this.paymentProperties.getOutbox().getRetryInterval();
        final double multiplier = this.paymentProperties.getOutbox().getBackoffMultiplier();
        final Duration maxInterval = this.paymentProperties.getOutbox().getMaxInterval();

        double delaySeconds = base.toSeconds() * Math.pow(multiplier, Math.min(retryCount - 1, 8));
        // Add +/- 15% jitter
        final double jitter = 0.85 + (RANDOM.nextDouble() * 0.3);
        delaySeconds = delaySeconds * jitter;

        final long cappedSeconds = Math.min((long) delaySeconds, maxInterval.toSeconds());
        return from.plusSeconds(Math.max(1, cappedSeconds));
    }
}

