package com.venkat.payment.scheduler;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentVerificationResponse;
import com.venkat.payment.repository.PaymentRepository;
import com.venkat.payment.service.PaymentStatusUpdateService;
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
 * Scheduled job performing fallback out-of-band gateway polling for payments that missed webhooks.
 * Employs gentle exponential backoff and leaves terminal resolution to the expiry flow.
 */
@Service
public class PaymentVerificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentVerificationScheduler.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStatusUpdateService statusUpdateService;
    private final PaymentProperties paymentProperties;
    private final Clock clock;

    public PaymentVerificationScheduler(final PaymentRepository paymentRepository,
                                        final PaymentGatewayRegistry gatewayRegistry,
                                        final PaymentStatusUpdateService statusUpdateService,
                                        final PaymentProperties paymentProperties,
                                        final Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.statusUpdateService = statusUpdateService;
        this.paymentProperties = paymentProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.verification.interval:PT30S}")
    @SchedulerLock(name = "PaymentVerificationScheduler", lockAtMostFor = "PT25S", lockAtLeastFor = "PT5S")
    public void verifyPendingPayments() {
        final Instant now = this.clock.instant();
        final Instant minAgeThreshold = now.minus(this.paymentProperties.getVerification().getMinAge());
        final int batchSize = this.paymentProperties.getVerification().getBatchSize();

        final List<Payment> candidates = this.paymentRepository.claimPaymentsNeedingVerification(
                now,
                minAgeThreshold,
                batchSize
        );

        if (candidates.isEmpty()) {
            return;
        }

        log.info("Verifying {} pending payment candidates via gateway", candidates.size());

        for (final Payment candidate : candidates) {
            verifySinglePayment(candidate, now);
        }
    }

    public void verifySinglePayment(final Payment payment, final Instant now) {
        final int currentAttempts = payment.getVerificationAttempts();
        final int maxAttempts = this.paymentProperties.getVerification().getMaxAttempts();

        // Check if exceeded max age/period
        final Instant maxPeriodThreshold = payment.getCreatedAt().plus(this.paymentProperties.getVerification().getMaxPeriod());
        if (now.isAfter(maxPeriodThreshold) || currentAttempts >= maxAttempts) {
            log.info("Payment [{}] reached max verification attempts ({}) or period. Leaving to expiry scheduler.",
                    payment.getPaymentReference(), currentAttempts);
            // Null out next_verification_at to stop polling
            this.paymentRepository.updateNextVerification(payment.getId(), null, currentAttempts);
            return;
        }

        try {
            // Query gateway outside transaction
            final PaymentGateway gateway = this.gatewayRegistry.getGateway(payment.getGateway());
            final PaymentVerificationResponse verified = gateway.verifyPayment(payment.getGatewayOrderId());

            if (verified.status() == PaymentStatus.SUCCESS) {
                log.info("Payment [{}] confirmed SUCCESS during verification polling!", payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.SUCCESS,
                        verified.paidAt(),
                        verified.gatewayPaymentId(),
                        false,
                        null,
                        false
                );
            } else if (verified.status() == PaymentStatus.FAILED) {
                log.info("Payment [{}] confirmed FAILED during verification polling.", payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.FAILED,
                        null,
                        verified.gatewayPaymentId(),
                        false,
                        "GATEWAY_CONFIRMED_FAILED",
                        false
                );
            } else {
                // Still pending - calculate next backoff interval
                final List<Duration> backoffList = this.paymentProperties.getVerification().getBackoff();
                final Duration backoffDuration = (currentAttempts < backoffList.size())
                        ? backoffList.get(currentAttempts)
                        : backoffList.get(backoffList.size() - 1);

                final Instant nextVerificationAt = now.plus(backoffDuration);
                this.paymentRepository.updateNextVerification(payment.getId(), nextVerificationAt, currentAttempts + 1);
                log.debug("Payment [{}] still PENDING. Scheduled attempt {} for {}",
                        payment.getPaymentReference(), currentAttempts + 1, nextVerificationAt);
            }
        } catch (final Exception ex) {
            log.warn("Error verifying payment [{}] with gateway", payment.getPaymentReference(), ex);
            final Instant nextRetry = now.plusSeconds(30);
            this.paymentRepository.updateNextVerification(payment.getId(), nextRetry, currentAttempts + 1);
        }
    }
}

