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
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled job claiming pending payments past expiration plus grace period,
 * performing one authoritative gateway check before transitioning to EXPIRED with transactional outbox.
 */
@Service
public class PaymentExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryScheduler.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentStatusUpdateService statusUpdateService;
    private final PaymentProperties paymentProperties;
    private final Clock clock;

    public PaymentExpiryScheduler(final PaymentRepository paymentRepository,
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

    @Scheduled(fixedDelayString = "${payment.expiry.interval:PT60S}")
    @SchedulerLock(name = "PaymentExpiryScheduler", lockAtMostFor = "PT55S", lockAtLeastFor = "PT5S")
    public void processExpiredPayments() {
        final Instant now = this.clock.instant();
        final Instant expirationThreshold = now.minus(this.paymentProperties.getExpiry().getGrace());
        final int batchSize = this.paymentProperties.getExpiry().getBatchSize();

        final List<Payment> expiredCandidates = this.paymentRepository.claimExpiredPendingPayments(
                expirationThreshold,
                batchSize
        );

        if (expiredCandidates.isEmpty()) {
            return;
        }

        log.info("Processing {} expired payment candidates", expiredCandidates.size());

        for (final Payment candidate : expiredCandidates) {
            expireSinglePayment(candidate);
        }
    }

    public void expireSinglePayment(final Payment payment) {
        try {
            // Verify with gateway ONCE first outside transaction to catch late payment
            final PaymentGateway gateway = this.gatewayRegistry.getGateway(payment.getGateway());
            final PaymentVerificationResponse verified = gateway.verifyPayment(payment.getGatewayOrderId());

            if (verified.status() == PaymentStatus.SUCCESS) {
                log.info("Late payment discovered during expiry check for payment [{}]! Transitioning to SUCCESS.",
                        payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.SUCCESS,
                        verified.paidAt(),
                        verified.gatewayPaymentId(),
                        true,
                        "LATE_SUCCESS",
                        true
                );
            } else if (verified.status() == PaymentStatus.FAILED) {
                log.info("Gateway confirmed payment [{}] failed during expiry check.", payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.FAILED,
                        null,
                        verified.gatewayPaymentId(),
                        false,
                        "GATEWAY_FAILED",
                        false
                );
            } else {
                // Not paid - transition PENDING -> EXPIRED in one transaction with outbox
                log.info("Expiring unpaid payment [{}]", payment.getPaymentReference());
                this.statusUpdateService.transitionStatusWithOutbox(
                        payment,
                        PaymentStatus.EXPIRED,
                        null,
                        null,
                        false,
                        null,
                        false
                );
            }
        } catch (final Exception ex) {
            log.error("Failed to process expiry for payment [{}]", payment.getPaymentReference(), ex);
        }
    }
}
