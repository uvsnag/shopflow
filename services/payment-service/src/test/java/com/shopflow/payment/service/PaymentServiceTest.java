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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-04 (never charged twice), NFR-10 (Idempotency-Key), ADR 0011 (amount from snapshot), refund queue. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock RefundRepository refundRepository;
    @Mock OrderSnapshotRepository snapshotRepository;
    @Mock FakeProviderClient provider;
    @Mock OutboxService outbox;
    @Mock TransactionTemplate tx;

    PaymentService service;

    private Payment saved;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, refundRepository, snapshotRepository, provider, outbox, tx, 3);
        // run transaction bodies inline
        lenient().doAnswer(inv -> {
            inv.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        lenient().when(tx.execute(any())).thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        lenient().when(paymentRepository.findById(500L)).thenAnswer(inv -> Optional.ofNullable(saved));
        lenient().when(paymentRepository.lockById(500L)).thenAnswer(inv -> Optional.ofNullable(saved));
    }

    private OrderSnapshot reservedOrder(long userId, long amount) {
        return new OrderSnapshot(42L, userId, amount, OrderSnapshot.Status.RESERVED);
    }

    /** Happy-path preconditions for createPayment(1, 42, "k"); the saved row gets id 500. */
    private void readyToPay(long amount) {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, amount)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });
    }

    // ---- createPayment

    @Test
    void missingKeyIsRejected() {
        assertThrows(BadRequestException.class, () -> service.createPayment(1L, 42L, ""));
    }

    @Test
    void sameKeyReplaysTheSamePaymentWithoutChargingAgain() {
        Payment existing = new Payment(1L, 42L, 100L, "k");
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.of(existing));

        assertSame(existing, service.createPayment(1L, 42L, "k"));

        verifyNoInteractions(provider);
    }

    @Test
    void sameKeyWithAnotherOrderIsRejected() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.of(new Payment(1L, 42L, 100L, "k")));

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 43L, "k"));
        verifyNoInteractions(provider);
    }

    @Test
    void anotherUsersKeyDoesNotReplayTheirPayment() {
        // the lookup is scoped by user: user 2 sending user 1's key is a fresh request
        when(paymentRepository.findByUserIdAndIdempotencyKey(2L, "k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));

        assertThrows(NotFoundException.class, () -> service.createPayment(2L, 42L, "k"));
        verify(paymentRepository, never()).findByUserIdAndIdempotencyKey(1L, "k");
    }

    @Test
    void unknownOrderIsNotReadyYet() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
    }

    @Test
    void someoneElsesOrderLooksNonexistent() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(7L, 100L)));

        assertThrows(NotFoundException.class, () -> service.createPayment(1L, 42L, "k"));
    }

    @Test
    void cancelledOrUnreservedOrdersCannotBePaid() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        OrderSnapshot cancelled = new OrderSnapshot(42L, 1L, 100L, OrderSnapshot.Status.CANCELLED);
        OrderSnapshot created = new OrderSnapshot(42L, 1L, 100L, OrderSnapshot.Status.CREATED);
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(cancelled), Optional.of(created));

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
        verifyNoInteractions(provider);
    }

    @Test
    void secondPaymentForTheSameOrderIsRejected() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k2")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(new Payment(1L, 42L, 100L, "k1")));

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k2"));
        verifyNoInteractions(provider);
    }

    @Test
    void amountComesFromTheSnapshotAndRowIsCommittedBeforeCharging() {
        readyToPay(250_000L);
        when(provider.charge(500L, 42L, 250_000L)).thenReturn(new FakeProviderClient.ChargeResponse("bank_1", "PROCESSING"));

        Payment payment = service.createPayment(1L, 42L, "k");

        assertEquals(250_000L, payment.getAmount());
        assertEquals(Payment.Status.PENDING, payment.getStatus(), "settled later by the webhook");
        assertEquals("bank_1", payment.getProviderRef());
        verify(provider).charge(500L, 42L, 250_000L);
    }

    @Test
    void providerOutageMarksPaymentFailedAndEmitsEvent() {
        readyToPay(100L);
        when(provider.charge(anyLong(), anyLong(), anyLong())).thenThrow(new RuntimeException("circuit open"));

        Payment payment = service.createPayment(1L, 42L, "k");

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_FAILED),
                argThat(p -> "provider unavailable".equals(p.get("reason"))));
    }

    @Test
    void losingTheInsertRaceReturnsTheWinnersPayment() {
        Payment winner = new Payment(1L, 42L, 100L, "k");
        when(paymentRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty(), Optional.of(winner));
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertSame(winner, service.createPayment(1L, 42L, "k"));
        verifyNoInteractions(provider);
    }

    @Test
    void webhookThatAlreadySettledIsNotOverwrittenByThePostChargeUpdate() {
        readyToPay(100L);
        // the webhook lands while charge() is still returning
        when(provider.charge(500L, 42L, 100L)).thenAnswer(inv -> {
            service.handleProviderResult(500L, "bank_1", true);
            return new FakeProviderClient.ChargeResponse("bank_1", "PROCESSING");
        });

        Payment payment = service.createPayment(1L, 42L, "k");

        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus());
        verify(paymentRepository, atLeast(2)).lockById(500L); // both writers went through the row lock
    }

    @Test
    void providerTimeoutDoesNotFailAPaymentTheWebhookAlreadySettled() {
        readyToPay(100L);
        when(provider.charge(500L, 42L, 100L)).thenAnswer(inv -> {
            service.handleProviderResult(500L, "bank_1", true);
            throw new RuntimeException("read timeout");
        });

        assertEquals(Payment.Status.SUCCEEDED, service.createPayment(1L, 42L, "k").getStatus());
        verify(outbox, never()).append(any(), any(), eq(EventTypes.PAYMENT_FAILED), any());
    }

    // ---- webhook

    private Payment payment(Payment.Status status) {
        Payment payment = new Payment(1L, 42L, 100L, "k");
        ReflectionTestUtils.setField(payment, "id", 500L);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Test
    void webhookSettlesOnceAndEmitsPaymentSucceeded() {
        Payment payment = payment(Payment.Status.PENDING);
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", true);
        service.handleProviderResult(500L, "bank_1", true); // duplicate webhook

        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void declinedWebhookEmitsPaymentFailed() {
        Payment payment = payment(Payment.Status.PENDING);
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", false);

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_FAILED), any());
    }

    @Test
    void successAfterWeGaveUpIsRecordedSoTheMoneyGetsRefunded() {
        // our charge call timed out -> FAILED, but the bank did take the money
        Payment payment = payment(Payment.Status.FAILED);
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", true);

        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus());
        // order-service answers PaymentSucceeded on a cancelled order with OrderCancelled -> refund
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void lateDeclineForFailedPaymentIsIgnored() {
        Payment payment = payment(Payment.Status.FAILED);
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", false);

        verifyNoInteractions(outbox);
    }

    // ---- refunds: queued in the caller's transaction, executed by the job

    private Payment succeededPayment() {
        Payment payment = payment(Payment.Status.SUCCEEDED);
        payment.setProviderRef("bank_1");
        return payment;
    }

    private static Refund refund(long id, Payment payment, String reason) {
        Refund refund = new Refund(payment.getId(), payment.getAmount(), reason);
        ReflectionTestUtils.setField(refund, "id", id);
        return refund;
    }

    @Test
    void cancelledPaidOrderQueuesARefundWithoutCallingTheBank() {
        when(paymentRepository.lockByOrderId(42L)).thenReturn(Optional.of(succeededPayment()));
        when(refundRepository.existsByPaymentIdAndStatusIn(eq(500L), any())).thenReturn(false);

        service.refundByOrderId(42L, "customer cancel after payment");

        verify(refundRepository).save(argThat(r -> r.isPending() && r.getPaymentId() == 500L
                && "customer cancel after payment".equals(r.getReason())));
        verifyNoInteractions(provider); // no remote call inside the Kafka listener's transaction
    }

    @Test
    void secondRefundRequestForTheSamePaymentIsIgnored() {
        when(paymentRepository.lockByOrderId(42L)).thenReturn(Optional.of(succeededPayment()));
        when(refundRepository.existsByPaymentIdAndStatusIn(eq(500L), any())).thenReturn(true);

        service.refundByOrderId(42L, "again");

        verify(refundRepository, never()).save(any());
    }

    @Test
    void unpaidOrderHasNothingToRefund() {
        when(paymentRepository.lockByOrderId(42L)).thenReturn(Optional.of(payment(Payment.Status.PENDING)));
        service.refundByOrderId(42L, "x");
        verifyNoInteractions(provider, refundRepository);
    }

    @Test
    void adminCanOnlyRefundSucceededPayments() {
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment(Payment.Status.PENDING)));
        assertThrows(ConflictException.class, () -> service.refundByPaymentId(500L, "admin refund"));
    }

    @Test
    void jobRefundsWithTheRefundIdAsIdempotencyKeyAndEmitsOneEvent() {
        Payment payment = succeededPayment();
        Refund pending = refund(9L, payment, "customer cancel");
        when(refundRepository.findTop20ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(eq(Refund.Status.PENDING), any()))
                .thenReturn(List.of(pending));
        when(refundRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        assertEquals(1, service.processDueRefunds());

        verify(provider).refund("refund-9", "bank_1", 100L);
        assertEquals(Refund.Status.SUCCEEDED, pending.getStatus());
        assertEquals(Payment.Status.REFUNDED, payment.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_REFUNDED),
                argThat(p -> "customer cancel".equals(p.get("reason"))));
    }

    @Test
    void failedBankCallIsRetriedWithBackoffThenGivesUp() {
        Payment payment = succeededPayment();
        Refund pending = refund(9L, payment, "customer cancel");
        when(refundRepository.findTop20ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(eq(Refund.Status.PENDING), any()))
                .thenReturn(List.of(pending));
        when(refundRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));
        when(provider.refund(any(), any(), anyLong())).thenThrow(new RuntimeException("bank down"));

        service.processDueRefunds();

        assertTrue(pending.isPending());
        assertEquals(1, pending.getAttempts());
        assertEquals("bank down", pending.getLastError());
        assertTrue(pending.getNextAttemptAt().isAfter(Instant.now()), "next try is pushed back");
        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus(), "not refunded yet");
        verify(outbox, never()).append(any(), any(), eq(EventTypes.PAYMENT_REFUNDED), any());

        service.processDueRefunds();
        service.processDueRefunds(); // attempt 3 = max
        assertEquals(Refund.Status.FAILED, pending.getStatus());
        assertEquals(3, pending.getAttempts());
    }

    @Test
    void refundSettledByAnotherReplicaDuringTheBankCallIsNotAppliedTwice() {
        Payment payment = succeededPayment();
        Refund pending = refund(9L, payment, "x");
        when(refundRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));
        when(provider.refund(any(), any(), anyLong())).thenAnswer(inv -> {
            pending.succeed(); // the other replica's settle step committed first
            return new FakeProviderClient.ChargeResponse("bank_1", "SUCCEEDED");
        });

        service.processRefund(9L, 500L);

        assertEquals(0, pending.getAttempts());
        verifyNoInteractions(outbox);
    }

    @Test
    void refundOfAPaymentThatIsNoLongerRefundableFailsWithoutCallingTheBank() {
        Payment payment = succeededPayment();
        payment.refund();
        Refund pending = refund(9L, payment, "x");
        when(refundRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(paymentRepository.lockById(500L)).thenReturn(Optional.of(payment));

        service.processRefund(9L, 500L);

        assertEquals(Refund.Status.FAILED, pending.getStatus());
        verifyNoInteractions(provider, outbox);
    }

    // ---- snapshot (ADR 0011): atomic upserts, the merge rules live in SQL

    @Test
    void snapshotEventsBecomeUpserts() {
        service.markOrderReserved(42L);
        service.rememberOrder(42L, 1L, 100L);
        service.markOrderCancelled(42L);

        verify(snapshotRepository).upsertReserved(42L);
        verify(snapshotRepository).upsertCreated(42L, 1L, 100L);
        verify(snapshotRepository).upsertCancelled(42L);
        verify(snapshotRepository, never()).save(any());
    }
}
