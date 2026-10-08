package com.venkat.payment.api;

import com.venkat.payment.service.PaymentWebhookService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller receiving signed webhook callbacks from payment gateways.
 * Strictly verifies signatures on raw bytes before any parsing.
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    public PaymentWebhookController(final PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{gateway}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable("gateway") final String gateway,
            @RequestHeader(value = "X-Signature", required = false) final String signature,
            @RequestBody final byte[] rawBody) {
        if (signature == null || signature.isBlank()) {
            throw new UnauthorizedException("Missing X-Signature header");
        }

        final String result = this.webhookService.processWebhook(gateway, rawBody, signature);
        return ResponseEntity.ok(Map.of("status", "ok", "result", result));
    }
}

