package com.venkat.payment.service;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.repository.BusinessProcessRepository;
import com.venkat.payment.repository.BusinessProcessRepository.BusinessProcessRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Worker simulating downstream business process execution (e.g. inventory allocation, ERP fulfillment).
 * Even if downstream execution fails or exhausts retries, the authoritative PAYMENT STATUS IS NEVER DOWNGRADED.
 */
@Service
public class BusinessProcessWorker {

    private static final Logger log = LoggerFactory.getLogger(BusinessProcessWorker.class);

    private final BusinessProcessRepository businessProcessRepository;
    private final PaymentProperties paymentProperties;
    private final Clock clock;

    // Test hook allowing failure injection during integration tests
    private volatile boolean failForTesting = false;

    public BusinessProcessWorker(final BusinessProcessRepository businessProcessRepository,
                                 final PaymentProperties paymentProperties,
                                 final Clock clock) {
        this.businessProcessRepository = businessProcessRepository;
        this.paymentProperties = paymentProperties;
        this.clock = clock;
    }

    public void setFailForTesting(final boolean failForTesting) {
        this.failForTesting = failForTesting;
    }

    @Scheduled(fixedDelayString = "${payment.business-process.poll-interval:PT5S}")
    @SchedulerLock(name = "BusinessProcessWorker", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void processPendingTasks() {
        final List<BusinessProcessRecord> tasks = this.businessProcessRepository.claimPendingProcesses(20);
        if (tasks.isEmpty()) {
            return;
        }

        for (final BusinessProcessRecord task : tasks) {
            executeTask(task);
        }
    }

    public void executeTask(final BusinessProcessRecord task) {
        this.businessProcessRepository.markProcessing(task.id());
        log.info("Executing downstream process [{}] for payment [{}]", task.processType(), task.paymentId());

        try {
            if (this.failForTesting) {
                throw new RuntimeException("Simulated downstream failure for testing");
            }

            // Simulate fulfillment logic (e.g., call ERP / update order table)
            log.info("Business process [{}] successfully fulfilled for payment [{}]",
                    task.processType(), task.paymentId());
            this.businessProcessRepository.markCompleted(task.id());
        } catch (final Exception ex) {
            final int nextRetryCount = task.retryCount() + 1;
            final int maxRetries = this.paymentProperties.getBusinessProcess().getMaxRetries();
            final boolean permanentlyFailed = nextRetryCount >= maxRetries;

            final Instant now = this.clock.instant();
            final long delaySeconds = (long) (5 * Math.pow(this.paymentProperties.getBusinessProcess().getBackoffMultiplier(), nextRetryCount));
            final Instant nextAttemptAt = now.plusSeconds(delaySeconds);

            this.businessProcessRepository.recordFailure(
                    task.id(),
                    nextRetryCount,
                    nextAttemptAt,
                    ex.getMessage(),
                    permanentlyFailed
            );

            if (permanentlyFailed) {
                log.error("Downstream process [{}] on payment [{}] PERMANENTLY FAILED after {} retries. Payment status remains intact.",
                        task.processType(), task.paymentId(), nextRetryCount, ex);
            } else {
                log.warn("Downstream process [{}] on payment [{}] failed (attempt {}/{}). Retrying at {}.",
                        task.processType(), task.paymentId(), nextRetryCount, maxRetries, nextAttemptAt);
            }
        }
    }
}

