package com.venkat.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.config.ClockConfig;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.security.SecurityConfig;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({GlobalExceptionHandler.class, ClockConfig.class, PaymentProperties.class, SecurityConfig.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("POST /api/v1/payments missing JWT token returns 401 UNAUTHORIZED")
    void missingJwtReturns401() throws Exception {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        this.mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "KEY-VALID-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/payments missing Idempotency-Key returns 400 MISSING_IDEMPOTENCY_KEY")
    void missingIdempotencyKeyReturns400() throws Exception {
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-1", new BigDecimal("500.00"), "INR", "CUST-1");

        this.mockMvc.perform(post("/api/v1/payments")
                        .with(jwt().authorities(() -> "SCOPE_payments:create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("POST /api/v1/payments with invalid input returns 400 VALIDATION_ERROR")
    void invalidInputReturns400() throws Exception {
        final String invalidPayload = """
                {
                    "orderId": "",
                    "amount": -50.00,
                    "currency": "INR"
                }
                """;

        this.mockMvc.perform(post("/api/v1/payments")
                        .with(jwt().authorities(() -> "SCOPE_payments:create"))
                        .header("Idempotency-Key", "KEY-VALID-12345")
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

        when(this.paymentService.createPayment(eq("KEY-VALID-12345"), any())).thenReturn(response);

        this.mockMvc.perform(post("/api/v1/payments")
                        .with(jwt().authorities(() -> "SCOPE_payments:create"))
                        .header("Idempotency-Key", "KEY-VALID-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentReference").value("PAY-ABC123456789"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} matching token subject returns 200 with Cache-Control: no-store")
    void getPaymentOwnerSuccess() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final Payment payment = new Payment(
                paymentId, "PAY-ABC123456789", "ORD-1", "customer-alice", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", "fake_pay_1", PaymentStatus.SUCCESS, "UPI", "upi://...", Instant.now().plusSeconds(900),
                Instant.now(), false, null, 0, null, 1L, Instant.now(), Instant.now()
        );

        when(this.paymentService.getPaymentEntity(paymentId)).thenReturn(payment);

        this.mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .with(jwt().jwt(j -> j.subject("customer-alice").claim("scope", "payments:read"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} with different token subject returns 404 PAYMENT_NOT_FOUND (ownership concealed)")
    void getPaymentForeignOwnerReturns404() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final Payment payment = new Payment(
                paymentId, "PAY-ABC123456789", "ORD-1", "customer-alice", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", "fake_pay_1", PaymentStatus.SUCCESS, "UPI", "upi://...", Instant.now().plusSeconds(900),
                Instant.now(), false, null, 0, null, 1L, Instant.now(), Instant.now()
        );

        when(this.paymentService.getPaymentEntity(paymentId)).thenReturn(payment);

        this.mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .with(jwt().jwt(j -> j.subject("customer-bob").claim("scope", "payments:read"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} with payments:internal scope bypasses ownership check")
    void getPaymentInternalScopeBypassesOwnership() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final Payment payment = new Payment(
                paymentId, "PAY-ABC123456789", "ORD-1", "customer-alice", new BigDecimal("500.00"), "INR", "fake",
                "fake_ord_1", "fake_pay_1", PaymentStatus.SUCCESS, "UPI", "upi://...", Instant.now().plusSeconds(900),
                Instant.now(), false, null, 0, null, 1L, Instant.now(), Instant.now()
        );

        when(this.paymentService.getPaymentEntity(paymentId)).thenReturn(payment);

        this.mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .with(jwt().jwt(j -> j.subject("internal-worker").claim("scope", "payments:internal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{id}/cancel cancels payment when caller has payments:cancel scope")
    void cancelPaymentSuccess() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final PaymentResponse cancelledResponse = new PaymentResponse(
                paymentId, "PAY-ABC123456789", "ORD-1", PaymentStatus.CANCELLED,
                new BigDecimal("500.00"), "INR", null, Instant.now()
        );

        when(this.paymentService.cancelPayment(eq(paymentId), any(), any(Boolean.class)))
                .thenReturn(cancelledResponse);

        this.mockMvc.perform(post("/api/v1/payments/" + paymentId + "/cancel")
                        .with(jwt().jwt(j -> j.subject("client-app").claim("scope", "payments:cancel"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{id}/refund refunds payment when caller has payments:internal and valid Idempotency-Key")
    void refundPaymentSuccess() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final RefundPaymentResponse refundResponse = new RefundPaymentResponse(
                paymentId, PaymentStatus.REFUNDED, "rfnd_test123",
                new BigDecimal("500.00"), "INR", Instant.now()
        );

        when(this.paymentService.refundPayment(eq(paymentId), eq("REFUND-IDEMP-KEY-12345678"), any(RefundPaymentRequest.class)))
                .thenReturn(refundResponse);

        final RefundPaymentRequest body = new RefundPaymentRequest(new BigDecimal("500.00"), "Customer requested refund");

        this.mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .header("Idempotency-Key", "REFUND-IDEMP-KEY-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(body))
                        .with(jwt().jwt(j -> j.subject("support-agent").claim("scope", "payments:internal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.refundId").value("rfnd_test123"))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{id}/refund without Idempotency-Key fails with 400 MISSING_IDEMPOTENCY_KEY")
    void refundPaymentMissingIdempotencyKey() throws Exception {
        final UUID paymentId = UUID.randomUUID();
        final RefundPaymentRequest body = new RefundPaymentRequest(new BigDecimal("500.00"), "Customer requested refund");

        this.mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(body))
                        .with(jwt().jwt(j -> j.subject("support-agent").claim("scope", "payments:internal"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }
}
