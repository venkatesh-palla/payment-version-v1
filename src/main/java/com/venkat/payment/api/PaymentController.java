package com.venkat.payment.api;

import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for payment creation and retrieval.
 * Controllers only validate, delegate, and map.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentProperties paymentProperties;

    public PaymentController(final PaymentService paymentService,
                             final PaymentProperties paymentProperties) {
        this.paymentService = paymentService;
        this.paymentProperties = paymentProperties;
    }

    @PostMapping
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @RequestHeader(value = "X-API-Key", required = false) final String apiKey,
            @Valid @RequestBody final CreatePaymentRequest request) {
        authenticateApiKey(apiKey);
        final CreatePaymentResponse response = this.paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable("paymentId") final UUID paymentId) {
        final PaymentResponse response = this.paymentService.getPayment(paymentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private void authenticateApiKey(final String apiKey) {
        if (apiKey == null || !apiKey.equals(this.paymentProperties.getApiKey())) {
            throw new UnauthorizedException("Invalid or missing X-API-Key header");
        }
    }
}

