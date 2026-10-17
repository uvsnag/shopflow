package com.shopflow.payment.api;

import com.shopflow.common.web.GlobalExceptionHandler;
import com.shopflow.payment.config.SecurityConfig;
import com.shopflow.payment.domain.Payment;
import com.shopflow.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NFR-06 (signed webhook), NFR-10 (Idempotency-Key), role checks on refunds. */
@WebMvcTest(controllers = {PaymentController.class, WebhookController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "shopflow.webhook-secret=test-secret")
class PaymentApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean PaymentService paymentService;

    // ---- webhook

    @Test
    void webhookWithValidSignatureIsAccepted() throws Exception {
        String body = "{\"paymentId\":500,\"providerRef\":\"bank_1\",\"status\":\"SUCCEEDED\"}";

        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", hmac(body, "test-secret")).content(body))
                .andExpect(status().isOk());

        verify(paymentService).handleProviderResult(500L, "bank_1", true);
    }

    @Test
    void webhookWithBadOrMissingSignatureIsRejected() throws Exception {
        String body = "{\"paymentId\":500,\"providerRef\":\"bank_1\",\"status\":\"SUCCEEDED\"}";

        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", hmac(body, "wrong-secret")).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        verify(paymentService, never()).handleProviderResult(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void tamperedBodyFailsTheSignature() throws Exception {
        String signed = "{\"paymentId\":500,\"providerRef\":\"bank_1\",\"status\":\"FAILED\"}";
        String tampered = "{\"paymentId\":500,\"providerRef\":\"bank_1\",\"status\":\"SUCCEEDED\"}";

        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", hmac(signed, "test-secret")).content(tampered))
                .andExpect(status().isUnauthorized());
    }

    // ---- pay

    @Test
    void payNeedsIdentityAndIdempotencyKey() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k").content("{\"orderId\":42}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER").content("{\"orderId\":42}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .header("Idempotency-Key", "k").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payReturnsAcceptedWithServerSideAmount() throws Exception {
        Payment payment = new Payment(1L, 42L, 250_000L, "k");
        ReflectionTestUtils.setField(payment, "id", 500L);
        when(paymentService.createPayment(1L, 42L, "k")).thenReturn(payment);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .header("Idempotency-Key", "k").content("{\"orderId\":42,\"amount\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").value(500))
                .andExpect(jsonPath("$.amount").value(250000))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void onlyAdminsRefund() throws Exception {
        mvc.perform(post("/admin/payments/500/refund").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/payments/500/refund").header("X-User-Id", "9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
        verify(paymentService).refundByPaymentId(500L, "admin refund");
    }

    private static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
