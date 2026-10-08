package com.venkat.payment.api;

import com.venkat.payment.config.ClockConfig;
import com.venkat.payment.service.PaymentWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentWebhookController.class)
@Import({GlobalExceptionHandler.class, ClockConfig.class})
class PaymentWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentWebhookService webhookService;

    @Test
    @DisplayName("Webhook missing X-Signature header returns 401 UNAUTHORIZED")
    void missingSignatureHeaderReturns401() throws Exception {
        this.mockMvc.perform(post("/api/v1/payments/webhook/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt_1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Webhook with invalid signature returns 401 UNAUTHORIZED")
    void invalidSignatureReturns401() throws Exception {
        when(this.webhookService.processWebhook(eq("fake"), any(), eq("invalid_sig")))
                .thenThrow(new UnauthorizedException("Invalid webhook signature"));

        this.mockMvc.perform(post("/api/v1/payments/webhook/fake")
                        .header("X-Signature", "invalid_sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt_1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Webhook with valid signature returns 200 OK")
    void validWebhookReturns200() throws Exception {
        when(this.webhookService.processWebhook(eq("fake"), any(), eq("valid_sig")))
                .thenReturn("processed");

        this.mockMvc.perform(post("/api/v1/payments/webhook/fake")
                        .header("X-Signature", "valid_sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt_1\",\"gatewayOrderId\":\"ord_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.result").value("processed"));
    }
}

