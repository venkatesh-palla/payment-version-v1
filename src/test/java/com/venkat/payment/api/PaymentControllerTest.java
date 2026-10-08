package com.venkat.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.config.ClockConfig;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({GlobalExceptionHandler.class, ClockConfig.class, PaymentProperties.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("POST /api/v1/payments missing X-API-Key returns 401 UNAUTHORIZED")
    void missingApiKeyReturns401() throws Exception {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        this.mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("POST /api/v1/payments with invalid input (negative amount) returns 400 VALIDATION_ERROR")
    void invalidInputReturns400() throws Exception {
        final String invalidPayload = """
                {
                    "orderId": "",
                    "amount": -50.00,
                    "currency": "INR"
                }
                """;

        this.mockMvc.perform(post("/api/v1/payments")
                        .header("X-API-Key", "dev-api-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/payments valid request returns 201 CREATED")
    void createPaymentReturns201() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");
        final CreatePaymentResponse response = new CreatePaymentResponse(
                paymentId, "PAY-ABC123456789", PaymentStatus.PENDING, new BigDecimal("500.00"), "INR", "upi://...", Instant.now().plusSeconds(900)
        );

        when(this.paymentService.createPayment(any())).thenReturn(response);

        this.mockMvc.perform(post("/api/v1/payments")
                        .header("X-API-Key", "dev-api-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentReference").value("PAY-ABC123456789"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} returns 200 with Cache-Control: no-store")
    void getPaymentReturns200WithNoStore() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final PaymentResponse response = new PaymentResponse(
                paymentId, "PAY-ABC123456789", "ORD-1", PaymentStatus.SUCCESS, new BigDecimal("500.00"), "INR", Instant.now(), Instant.now().plusSeconds(900)
        );

        when(this.paymentService.getPayment(paymentId)).thenReturn(response);

        this.mockMvc.perform(get("/api/v1/payments/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} returns 404 when not found")
    void getPaymentNotFoundReturns404() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        when(this.paymentService.getPayment(paymentId)).thenThrow(new PaymentNotFoundException(paymentId));

        this.mockMvc.perform(get("/api/v1/payments/" + paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}

