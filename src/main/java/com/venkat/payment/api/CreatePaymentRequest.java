package com.venkat.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request payload for creating a new payment session.
 */
public record CreatePaymentRequest(
        @NotBlank(message = "orderId is required")
        @Size(max = 64, message = "orderId must not exceed 64 characters")
        String orderId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        @Digits(integer = 12, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
        String currency,

        @Size(max = 64, message = "customerId must not exceed 64 characters")
        String customerId
) {
}

