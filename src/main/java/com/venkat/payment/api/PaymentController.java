package com.venkat.payment.api;

import com.venkat.payment.domain.Payment;
import com.venkat.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for payment creation and retrieval with JWT scope and ownership authorization.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(final PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @Valid @RequestBody final CreatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.trim().length() < 8 || idempotencyKey.trim().length() > 128) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required and must be between 8 and 128 characters");
        }

        final CreatePaymentResponse response = this.paymentService.createPayment(idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable("paymentId") final UUID paymentId,
            @AuthenticationPrincipal final Jwt jwt) {

        final Payment payment = this.paymentService.getPaymentEntity(paymentId);

        // Ownership enforcement: callers with only payments:read cannot read payments of other customers
        if (jwt != null) {
            final String scope = jwt.getClaimAsString("scope");
            final boolean isInternal = scope != null && scope.contains("payments:internal");

            if (!isInternal) {
                final String callerSubject = jwt.getSubject();
                if (payment.getCustomerId() == null || !payment.getCustomerId().equals(callerSubject)) {
                    // Conceal existence to prevent customer resource enumeration
                    throw new PaymentNotFoundException(paymentId);
                }
            }
        }

        final PaymentResponse response = new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getOrderId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaidAt(),
                payment.getExpiresAt()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable("paymentId") final UUID paymentId,
            @AuthenticationPrincipal final Jwt jwt) {

        String callerSubject = null;
        boolean isInternal = false;

        if (jwt != null) {
            final String scope = jwt.getClaimAsString("scope");
            isInternal = scope != null && scope.contains("payments:internal");
            callerSubject = jwt.getSubject();
        }

        final PaymentResponse response = this.paymentService.cancelPayment(paymentId, callerSubject, isInternal);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<RefundPaymentResponse> refundPayment(
            @PathVariable("paymentId") final UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @Valid @RequestBody(required = false) final RefundPaymentRequest request,
            @AuthenticationPrincipal final Jwt jwt) {

        if (jwt != null) {
            final String scope = jwt.getClaimAsString("scope");
            final boolean isInternal = scope != null && scope.contains("payments:internal");
            if (!isInternal) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Scope payments:internal is required to issue refunds"
                );
            }
        }

        if (idempotencyKey == null || idempotencyKey.trim().length() < 8 || idempotencyKey.trim().length() > 128) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for refunds");
        }

        final RefundPaymentResponse response = this.paymentService.refundPayment(paymentId, idempotencyKey.trim(), request);
        return ResponseEntity.ok(response);
    }
}
