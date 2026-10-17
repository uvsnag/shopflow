package com.shopflow.payment.api;

import com.shopflow.payment.domain.Payment;
import com.shopflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public record CreatePaymentRequest(@NotNull Long orderId, @NotNull @Min(1) Long amount) {}

    /** FR-08 + NFR-10. TODO: fetch the amount from order-service instead of trusting the client. */
    @PostMapping("/payments")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> pay(@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                   @RequestBody @Valid CreatePaymentRequest req) {
        Payment payment = paymentService.createPayment(req.orderId(), req.amount(), idempotencyKey);
        return Map.of("paymentId", payment.getId(),
                "orderId", payment.getOrderId(),
                "status", payment.getStatus().name());
    }

    /** FR-16: admin refund. */
    @PostMapping("/admin/payments/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> refund(@PathVariable Long id) {
        paymentService.refundByPaymentId(id, "admin refund");
        return Map.of("paymentId", id, "status", "REFUNDED");
    }
}
