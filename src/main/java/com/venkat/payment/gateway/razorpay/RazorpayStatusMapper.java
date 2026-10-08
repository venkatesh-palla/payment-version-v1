package com.venkat.payment.gateway.razorpay;

import com.venkat.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps provider-specific status strings to canonical domain PaymentStatus.
 * Unknown statuses are strictly mapped to PENDING with a WARN log, NEVER to SUCCESS.
 */
@Component
public class RazorpayStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(RazorpayStatusMapper.class);

    public PaymentStatus mapPaymentStatus(final String providerStatus) {
        if (providerStatus == null) {
            log.warn("Null Razorpay status encountered, defaulting to PENDING");
            return PaymentStatus.PENDING;
        }

        return switch (providerStatus.toLowerCase().trim()) {
            case "captured", "authorized" -> PaymentStatus.SUCCESS;
            case "failed" -> PaymentStatus.FAILED;
            case "created", "pending" -> PaymentStatus.PENDING;
            case "refunded" -> PaymentStatus.REFUNDED;
            default -> {
                log.warn("Unknown Razorpay payment status [{}]. Treating conservatively as PENDING; never guessing SUCCESS.",
                        providerStatus);
                yield PaymentStatus.PENDING;
            }
        };
    }

    public PaymentStatus mapQrStatus(final String qrStatus) {
        if (qrStatus == null) {
            return PaymentStatus.PENDING;
        }

        return switch (qrStatus.toLowerCase().trim()) {
            case "active" -> PaymentStatus.PENDING;
            case "closed" -> PaymentStatus.EXPIRED;
            default -> {
                log.warn("Unknown Razorpay QR status [{}]. Defaulting to PENDING.", qrStatus);
                yield PaymentStatus.PENDING;
            }
        };
    }
}

