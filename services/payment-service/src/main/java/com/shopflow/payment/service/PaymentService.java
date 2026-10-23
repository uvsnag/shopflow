package com.shopflow.payment.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.ConflictException;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.payment.client.FakeProviderClient;
import com.shopflow.payment.domain.OrderSnapshot;
import com.shopflow.payment.domain.Payment;
import com.shopflow.payment.domain.Refund;
import com.shopflow.payment.repository.OrderSnapshotRepository;
import com.shopflow.payment.repository.PaymentRepository;
import com.shopflow.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OrderSnapshotRepository snapshotRepository;
    private final FakeProviderClient providerClient;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final int refundMaxAttempts;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          OrderSnapshotRepository snapshotRepository,
                          FakeProviderClient providerClient,
                          OutboxService outbox,
                          TransactionTemplate tx,
                          @Value("${shopflow.refund-retry.max-attempts:5}") int refundMaxAttempts) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.snapshotRepository = snapshotRepository;
        this.providerClient = providerClient;
        this.outbox = outbox;
        this.tx = tx;
        this.refundMaxAttempts = refundMaxAttempts;
    }

    // ---------------------------------------------------------------- order snapshot (ADR 0011)

    /** OrderCreated: remember owner and amount. Never revives a cancelled order. */
    @Transactional
    public void rememberOrder(Long orderId, Long userId, Long amount) {
        snapshotRepository.findById(orderId).ifPresentOrElse(
                s -> s.fillFromOrderCreated(userId, amount),
                () -> snapshotRepository.save(new OrderSnapshot(orderId, userId, amount, OrderSnapshot.Status.CREATED)));
    }

    /** StockReserved: the order may now be paid. */
    @Transactional
    public void markOrderReserved(Long orderId) {
        snapshotRepository.findById(orderId).ifPresentOrElse(
                OrderSnapshot::markReserved,
                () -> snapshotRepository.save(new OrderSnapshot(orderId, null, null, OrderSnapshot.Status.RESERVED)));
    }

    /** OrderCancelled / StockReservationFailed: no (new) payment for this order. */
    @Transactional
    public void markOrderCancelled(Long orderId) {
        snapshotRepository.findById(orderId).ifPresentOrElse(
                OrderSnapshot::markCancelled,
                () -> snapshotRepository.save(new OrderSnapshot(orderId, null, null, OrderSnapshot.Status.CANCELLED)));
    }

    // ---------------------------------------------------------------- charge

    /**
     * FR-08 + NFR-10: create the payment idempotently, then ask the fake bank
     * to charge. The result arrives later via the signed webhook.
     *
     * The amount is read from the order snapshot, never from the request.
     *
     * Deliberately NOT one big @Transactional: the payment row is committed
     * first, so the unique constraints on (user_id, idempotency_key) and
     * order_id (BR-04) reject a concurrent double click BEFORE the provider is
     * called — not at commit time, after the bank was already charged twice.
     * It also means the DB transaction is not held open through up to ~9s of
     * provider retries.
     *
     * Idempotency-Key rules (NFR-10): the key is scoped to the caller, so one
     * user can never replay (or read) another user's payment. The lookup is a
     * fast path only — the INSERT is the real arbiter; the loser of a race
     * re-reads the winner's row. A key replayed with a DIFFERENT order is a
     * client bug and is rejected instead of silently returning the old payment.
     */
    public Payment createPayment(Long userId, Long orderId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        var existing = paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return sameRequest(existing.get(), orderId);
        }

        OrderSnapshot order = snapshotRepository.findById(orderId)
                .orElseThrow(() -> new ConflictException(
                        "Order " + orderId + " is not ready for payment yet, please retry"));
        if (order.getUserId() != null && !order.getUserId().equals(userId)) {
            throw new NotFoundException("Order " + orderId + " not found"); // BR-06: not yours
        }
        if (order.getStatus() == OrderSnapshot.Status.CANCELLED) {
            throw new ConflictException("Order " + orderId + " was cancelled and cannot be paid");
        }
        if (!order.isPayable()) {
            throw new ConflictException("Order " + orderId + " has no reserved stock yet, please retry");
        }
        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            throw new ConflictException("Order " + orderId + " already has a payment");
        }

        Long amount = order.getAmount();
        Payment payment;
        try {
            // repository.save runs in its own transaction and commits here
            payment = paymentRepository.save(new Payment(userId, orderId, amount, idempotencyKey));
        } catch (DataIntegrityViolationException raceLost) {
            // a concurrent request won: same user + same Idempotency-Key -> return
            // its payment (NFR-10); same order, different key -> BR-04 conflict
            return paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(winner -> sameRequest(winner, orderId))
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

    /** Replay is only valid for the same request: the same key with another order is rejected. */
    private static Payment sameRequest(Payment existing, Long orderId) {
        if (!existing.getOrderId().equals(orderId)) {
            throw new ConflictException("Idempotency-Key was already used for order " + existing.getOrderId());
        }
        return existing;
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

    // ---------------------------------------------------------------- refund (compensation)

    /** FR-10 / saga compensation: OrderCancelled for a paid order -> refund. */
    @Transactional
    public void refundByOrderId(Long orderId, String reason) {
        paymentRepository.findByOrderId(orderId)
                .filter(Payment::isSucceeded)
                .ifPresent(payment -> startRefund(payment, reason));
    }

    /** FR-16: admin refund. */
    @Transactional
    public void refundByPaymentId(Long paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        if (!payment.isSucceeded()) {
            throw new ConflictException("Only a succeeded payment can be refunded");
        }
        startRefund(payment, reason);
    }

    private void startRefund(Payment payment, String reason) {
        if (refundRepository.existsByPaymentIdAndStatus(payment.getId(), Refund.Status.PENDING)) {
            return; // a retry is already queued for this payment
        }
        Refund refund = refundRepository.save(new Refund(payment.getId(), payment.getAmount(), reason));
        attemptRefund(refund, payment);
    }

    /**
     * One call to the provider. Success: payment REFUNDED + PaymentRefunded
     * event (inventory returns the stock, order-service and notification react).
     * Failure: the refund stays PENDING and {@link #retryPendingRefunds} tries
     * again later; after the maximum number of attempts it is FAILED for a human.
     */
    void attemptRefund(Refund refund, Payment payment) {
        refund.recordAttempt();
        try {
            providerClient.refund(payment.getProviderRef(), payment.getAmount());
            refund.succeed();
            payment.refund();
            outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                    EventTypes.PAYMENT_REFUNDED,
                    Map.of("paymentId", payment.getId(), "amount", payment.getAmount(),
                            "reason", refund.getReason() == null ? "" : refund.getReason(),
                            "userId", payment.getUserId()));
        } catch (Exception e) {
            refund.recordError(e.getMessage());
            if (refund.getAttempts() >= refundMaxAttempts) {
                refund.fail();
                log.error("Refund {} for payment {} failed {} times — needs manual action",
                        refund.getId(), payment.getId(), refund.getAttempts(), e);
            } else {
                log.warn("Refund {} for payment {} failed (attempt {}), will retry",
                        refund.getId(), payment.getId(), refund.getAttempts(), e);
            }
        }
    }

    /** Runs from {@link RefundRetryJob}. Refunds younger than 30s are left to their first attempt. */
    @Transactional
    public int retryPendingRefunds() {
        int retried = 0;
        for (Refund refund : refundRepository.findByStatusAndCreatedAtBefore(
                Refund.Status.PENDING, Instant.now().minus(Duration.ofSeconds(30)))) {
            Payment payment = paymentRepository.findById(refund.getPaymentId()).orElse(null);
            if (payment == null) {
                refund.fail();
                continue;
            }
            if (!payment.isSucceeded()) {
                refund.succeed(); // another refund row already settled this payment
                continue;
            }
            attemptRefund(refund, payment);
            retried++;
        }
        return retried;
    }

    private void markFailed(Payment payment, String reason) {
        payment.fail();
        outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                EventTypes.PAYMENT_FAILED,
                Map.of("paymentId", payment.getId(), "reason", reason,
                        "userId", payment.getUserId()));
    }
}
