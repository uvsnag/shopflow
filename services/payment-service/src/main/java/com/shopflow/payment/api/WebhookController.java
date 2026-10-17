package com.shopflow.payment.api;

import com.shopflow.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * NFR-06: the fake provider signs the raw body with HMAC-SHA256 and sends the
 * signature in X-Signature. Calls with a bad signature are rejected.
 */
@RestController
public class WebhookController {

    private final PaymentService paymentService;
    private final String webhookSecret;

    public WebhookController(PaymentService paymentService,
                             @Value("${shopflow.webhook-secret:local-dev-secret}") String webhookSecret) {
        this.paymentService = paymentService;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(name = "X-Signature", required = false) String signature,
                                        @RequestBody String rawBody) {
        if (signature == null || !MessageDigest.isEqual(
                hmac(rawBody).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> body = parse(rawBody);
        paymentService.handleProviderResult(
                Long.valueOf(body.get("paymentId").toString()),
                String.valueOf(body.get("providerRef")),
                "SUCCEEDED".equals(body.get("status")));
        return ResponseEntity.ok().build();
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad webhook body", e);
        }
    }
}
