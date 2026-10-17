package com.shopflow.payment.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.ConflictException;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.payment.client.FakeProviderClient;
import com.shopflow.payment.domain.Payment;
import com.shopflow.payment.domain.Refund;
import com.shopflow.payment.repository.PaymentRepository;
import com.shopflow.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final FakeProviderClient providerClient;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          FakeProviderClient providerClient,
                          OutboxService outbox,
                          TransactionTemplate tx) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.providerClient = providerClient;
        this.outbox = outbox;
        this.tx = tx;
    }

    /**
     * FR-08 + NFR-10: create the payment idempotently, then ask the fake bank
     * to charge. The result arrives later via the signed webhook.
     *
     * Deliberately NOT one big @Transactional: the payment row is committed
     * first, so the unique constraints on idempotency_key and order_id (BR-04)
     * reject a concurrent double click BEFORE the provider is called — not at
     * commit time, after the bank was already charged twice. It also means the
     * DB transaction is not held open through up to ~9s of provider retries.
     */
    public Payment createPayment(Long userId, Long orderId, Long amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            throw new ConflictException("Order " + orderId + " already has a payment");
        }

        Payment payment;
        try {
            // repository.save runs in its own transaction and commits here
            payment = paymentRepository.save(new Payment(userId, orderId, amount, idempotencyKey));
        } catch (DataIntegrityViolationException raceLost) {
            // a concurrent request won: same Idempotency-Key -> return its
            // payment (NFR-10); same order, different key -> BR-04 conflict
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new ConflictException("Order " + orderId + " already has a payment"));
        }

        Long paymentId = payment.getId();
        try {
            var response = providerClient.charge(paymentId, orderId, amount);
            tx.executeWithoutResult(status -> paymentRepository.findById(paymentId)
                    .ifPresent(p -> p.setProviderRef(response.providerRef())));
        } catch (Exception providerDown) {
            // NFR-11: retries + circuit breaker already ran; mark failed
            log.warn("Provider charge failed for payment {}", paymentId, providerDown);
            tx.executeWithoutResult(status -> paymentRepository.findById(paymentId)
                    .ifPresent(p -> markFailed(p, "provider unavailable")));
        }
        return paymentRepository.findById(paymentId).orElse(payment);
    }

    /** Called by the webhook after the HMAC signature was verified (NFR-06). */
    @Transactional
    public void handleProviderResult(Long paymentId, String providerRef, boolean succeeded) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        if (!payment.isPending()) {
            return; // duplicate webhook, already settled
        }
        payment.setProviderRef(providerRef);
        if (succeeded) {
            payment.succeed();
            outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                    EventTypes.PAYMENT_SUCCEEDED,
                    Map.of("paymentId", payment.getId(), "amount", payment.getAmount(),
                            "userId", payment.getUserId()));
        } else {
            markFailed(payment, "provider declined");
        }
    }

    /** FR-10 / FR-16: refund a paid order. */
    @Transactional
    public void refundByOrderId(Long orderId, String reason) {
        paymentRepository.findByOrderId(orderId)
                .filter(Payment::isSucceeded)
                .ifPresent(payment -> doRefund(payment, reason));
    }

    @Transactional
    public void refundByPaymentId(Long paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        if (!payment.isSucceeded()) {
            throw new ConflictException("Only a succeeded payment can be refunded");
        }
        doRefund(payment, reason);
    }

    private void doRefund(Payment payment, String reason) {
        Refund refund = refundRepository.save(new Refund(payment.getId(), payment.getAmount()));
        try {
            providerClient.refund(payment.getProviderRef(), payment.getAmount());
            refund.succeed();
            payment.refund();
            outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                    EventTypes.PAYMENT_REFUNDED,
                    Map.of("paymentId", payment.getId(), "amount", payment.getAmount(),
                            "reason", reason, "userId", payment.getUserId()));
        } catch (Exception e) {
            refund.fail();
            log.error("Refund failed for payment {} — needs manual retry", payment.getId(), e);
            // TODO: retry job for failed refunds
        }
    }

    private void markFailed(Payment payment, String reason) {
        payment.fail();
        outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                EventTypes.PAYMENT_FAILED,
                Map.of("paymentId", payment.getId(), "reason", reason,
                        "userId", payment.getUserId()));
    }
}
