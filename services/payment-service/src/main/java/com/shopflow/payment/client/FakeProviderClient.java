package com.shopflow.payment.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * NFR-11: calls to the fake bank have a 3s timeout, 3 retries with backoff and
 * a circuit breaker (see resilience4j config in application.yml). The provider
 * answers asynchronously through a signed webhook.
 *
 * Retrying a money-moving POST is only safe because every call carries an
 * Idempotency-Key the provider de-duplicates on: a charge that timed out on
 * our side but went through at the bank is NOT charged again by the retry —
 * the bank answers with the original providerRef.
 */
@Component
public class FakeProviderClient {

    public record ChargeResponse(String providerRef, String status) {}

    private final RestClient restClient;
    private final String webhookUrl;

    public FakeProviderClient(@Value("${shopflow.provider-url:http://localhost:9090}") String providerUrl,
                              @Value("${shopflow.webhook-url:http://localhost:8085/payments/webhook}") String webhookUrl,
                              RestClient.Builder builder) {
        this.restClient = builder.baseUrl(providerUrl).build();
        this.webhookUrl = webhookUrl;
    }

    @Retry(name = "provider")
    @CircuitBreaker(name = "provider")
    public ChargeResponse charge(Long paymentId, Long orderId, Long amount) {
        return restClient.post()
                .uri("/charge")
                .header("Idempotency-Key", "payment-" + paymentId)
                .body(Map.of(
                        "paymentId", paymentId,
                        "orderId", orderId,
                        "amount", amount,
                        "webhookUrl", webhookUrl))
                .retrieve()
                .body(ChargeResponse.class);
    }

    @Retry(name = "provider")
    @CircuitBreaker(name = "provider")
    public ChargeResponse refund(String idempotencyKey, String providerRef, Long amount) {
        return restClient.post()
                .uri("/refund")
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("providerRef", providerRef, "amount", amount))
                .retrieve()
                .body(ChargeResponse.class);
    }
}
