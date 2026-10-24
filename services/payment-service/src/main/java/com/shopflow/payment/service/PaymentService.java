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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Concurrency model: every status change of a payment happens under
 * {@link PaymentRepository#lockById} (the webhook, the post-charge update,
 * refund settlement), and the bank is never called while a DB transaction is
 * open. Calls to the bank carry an Idempotency-Key, so a retry after a timeout
 * cannot charge or refund twice.
 */
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
        snapshotRepository.upsertCreated(orderId, userId, amount);
    }

    /** StockReserved: the order may now be paid. */
    @Transactional
    public void markOrderReserved(Long orderId) {
        snapshotRepository.upsertReserved(orderId);
    }

    /** OrderCancelled / StockReservationFailed: no (new) payment for this order. */
    @Transactional
    public void markOrderCancelled(Long orderId) {
        snapshotRepository.upsertCancelled(orderId);
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
     *
     * If the order is cancelled between the snapshot check and the charge,
     * the payment still goes through and the late-payment path refunds it
     * (order-service answers PaymentSucceeded on a cancelled order with
     * OrderCancelled).
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
            // the webhook may already have settled the payment: only fill in what is missing
            tx.executeWithoutResult(status -> paymentRepository.lockById(paymentId).ifPresent(p -> {
                if (p.getProviderRef() == null) {
                    p.setProviderRef(response.providerRef());
                }
            }));
        } catch (Exception providerDown) {
            // NFR-11: retries + circuit breaker already ran. Mark failed only if the
            // webhook has not settled it meanwhile; if the bank did charge after
            // all, the late webhook turns this into a refund (handleProviderResult).
            log.warn("Provider charge failed for payment {}", paymentId, providerDown);
            tx.executeWithoutResult(status -> paymentRepository.lockById(paymentId)
                    .filter(Payment::isPending)
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

    /**
     * Called by the webhook after the HMAC signature was verified (NFR-06).
     * The provider retries webhooks, so duplicates are normal and ignored.
     *
     * Special case: we marked the payment FAILED because the charge call timed
     * out, but the bank did take the money. The success is recorded and
     * PaymentSucceeded emitted; order-service sees a cancelled order and asks
     * for the refund (the late-payment path). Ignoring it would keep the
     * customer's money with no order.
     */
    @Transactional
    public void handleProviderResult(Long paymentId, String providerRef, boolean succeeded) {
        Payment payment = paymentRepository.lockById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        boolean lateSuccess = succeeded && payment.getStatus() == Payment.Status.FAILED;
        if (!payment.isPending() && !lateSuccess) {
            return; // duplicate webhook, already settled
        }
        payment.setProviderRef(providerRef);
        if (succeeded) {
            if (lateSuccess) {
                log.warn("Payment {} was marked failed but the bank charged it; it will be refunded", paymentId);
            }
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

    /**
     * FR-10 / saga compensation: OrderCancelled for a paid order -> queue a
     * refund. Runs in the Kafka listener's transaction, so the refund request
     * and the processed_events mark commit together; the bank is called later
     * by {@link RefundRetryJob}, never while the consumer holds a transaction.
     */
    @Transactional
    public void refundByOrderId(Long orderId, String reason) {
        paymentRepository.lockByOrderId(orderId)
                .filter(Payment::isSucceeded)
                .ifPresent(payment -> requestRefund(payment, reason));
    }

    /** FR-16: admin refund. Answers at once; the bank call happens in the background. */
    @Transactional
    public void refundByPaymentId(Long paymentId, String reason) {
        Payment payment = paymentRepository.lockById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        if (!payment.isSucceeded()) {
            throw new ConflictException("Only a succeeded payment can be refunded");
        }
        requestRefund(payment, reason);
    }

    /**
     * Caller holds the payment row lock, so two requests (customer cancel and
     * admin refund at the same moment) see each other's refund row; the partial
     * unique index on refunds is the final guard.
     */
    private void requestRefund(Payment payment, String reason) {
        if (refundRepository.existsByPaymentIdAndStatusIn(payment.getId(),
                List.of(Refund.Status.PENDING, Refund.Status.SUCCEEDED))) {
            return; // already queued or done
        }
        refundRepository.save(new Refund(payment.getId(), payment.getAmount(), reason));
    }

    /** Runs from {@link RefundRetryJob}: every refund whose (next) attempt is due. */
    public int processDueRefunds() {
        int processed = 0;
        for (Refund refund : refundRepository.findTop20ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                Refund.Status.PENDING, Instant.now())) {
            try {
                processRefund(refund.getId(), refund.getPaymentId());
                processed++;
            } catch (Exception e) {
                log.warn("Could not process refund {}, will retry", refund.getId(), e);
            }
        }
        return processed;
    }

    private record RefundCall(Long refundId, Long paymentId, String idempotencyKey, String providerRef, Long amount) {}

    /**
     * One attempt, in three steps so no transaction is open during the bank call:
     * read what to send; call the bank (Idempotency-Key = refund id, so two
     * replicas running the job, or a retry after a timeout, pay out once);
     * settle under the payment lock — only if the refund is still PENDING, so a
     * concurrent attempt that settled first is not applied twice.
     * Each step locks the payment row BEFORE reading the refund, so the refund
     * status it sees is the committed one.
     */
    void processRefund(Long refundId, Long paymentId) {
        RefundCall call = tx.execute(status -> {
            Payment payment = paymentRepository.lockById(paymentId).orElse(null);
            Refund refund = refundRepository.findById(refundId).filter(Refund::isPending).orElse(null);
            if (refund == null) {
                return null;
            }
            if (payment == null || !payment.isSucceeded()) {
                refund.recordError("payment is not refundable"
                        + (payment == null ? "" : " (status " + payment.getStatus() + ")"));
                refund.fail();
                return null;
            }
            return new RefundCall(refund.getId(), payment.getId(), refund.providerIdempotencyKey(),
                    payment.getProviderRef(), payment.getAmount());
        });
        if (call == null) {
            return;
        }

        Exception failure = null;
        try {
            providerClient.refund(call.idempotencyKey(), call.providerRef(), call.amount());
        } catch (Exception e) {
            failure = e;
        }

        Exception error = failure;
        tx.executeWithoutResult(status -> settleRefund(call, error));
    }

    private void settleRefund(RefundCall call, Exception error) {
        Payment payment = paymentRepository.lockById(call.paymentId()).orElseThrow();
        Refund refund = refundRepository.findById(call.refundId()).orElseThrow();
        if (!refund.isPending()) {
            return; // another replica settled this attempt first
        }
        refund.recordAttempt();
        if (error == null) {
            refund.succeed();
            payment.refund();
            outbox.append(EventTypes.TOPIC_PAYMENT_EVENTS, String.valueOf(payment.getOrderId()),
                    EventTypes.PAYMENT_REFUNDED,
                    Map.of("paymentId", payment.getId(), "amount", payment.getAmount(),
                            "reason", refund.getReason() == null ? "" : refund.getReason(),
                            "userId", payment.getUserId()));
            return;
        }
        refund.recordError(error.getMessage());
        if (refund.getAttempts() >= refundMaxAttempts) {
            refund.fail();
            log.error("Refund {} for payment {} failed {} times — needs manual action",
                    refund.getId(), payment.getId(), refund.getAttempts(), error);
        } else {
            refund.scheduleRetry(Instant.now());
            log.warn("Refund {} for payment {} failed (attempt {}), will retry at {}",
                    refund.getId(), payment.getId(), refund.getAttempts(), refund.getNextAttemptAt(), error);
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
