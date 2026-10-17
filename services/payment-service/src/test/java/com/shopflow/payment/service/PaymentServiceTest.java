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
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-04 (never charged twice), NFR-10 (Idempotency-Key), ADR 0011 (amount from snapshot), refund retry. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock RefundRepository refundRepository;
    @Mock OrderSnapshotRepository snapshotRepository;
    @Mock FakeProviderClient provider;
    @Mock OutboxService outbox;
    @Mock TransactionTemplate tx;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, refundRepository, snapshotRepository, provider, outbox, tx, 3);
        lenient().doAnswer(inv -> {
            inv.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 500L);
            return p;
        });
        lenient().when(paymentRepository.findById(500L)).thenAnswer(inv -> Optional.ofNullable(saved));
        lenient().doAnswer(inv -> { saved = inv.getArgument(0); saved = paymentRepository.save(saved); return saved; })
                .when(paymentRepository).saveAndFlush(any());
    }

    private Payment saved;

    private OrderSnapshot reservedOrder(long userId, long amount) {
        OrderSnapshot s = new OrderSnapshot(42L, userId, amount, OrderSnapshot.Status.CREATED);
        s.markReserved();
        return s;
    }

    // ---- createPayment

    @Test
    void missingKeyIsRejected() {
        assertThrows(BadRequestException.class, () -> service.createPayment(1L, 42L, ""));
    }

    @Test
    void sameKeyReplaysTheSamePaymentWithoutChargingAgain() {
        Payment existing = new Payment(1L, 42L, 100L, "k");
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.of(existing));

        assertSame(existing, service.createPayment(1L, 42L, "k"));

        verifyNoInteractions(provider);
    }

    @Test
    void unknownOrderIsNotReadyYet() {
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
    }

    @Test
    void someoneElsesOrderLooksNonexistent() {
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(7L, 100L)));

        assertThrows(NotFoundException.class, () -> service.createPayment(1L, 42L, "k"));
    }

    @Test
    void cancelledOrUnreservedOrdersCannotBePaid() {
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        OrderSnapshot cancelled = new OrderSnapshot(42L, 1L, 100L, OrderSnapshot.Status.CANCELLED);
        OrderSnapshot created = new OrderSnapshot(42L, 1L, 100L, OrderSnapshot.Status.CREATED);
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(cancelled), Optional.of(created));

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k"));
        verifyNoInteractions(provider);
    }

    @Test
    void secondPaymentForTheSameOrderIsRejected() {
        when(paymentRepository.findByIdempotencyKey("k2")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(new Payment(1L, 42L, 100L, "k1")));

        assertThrows(ConflictException.class, () -> service.createPayment(1L, 42L, "k2"));
        verifyNoInteractions(provider);
    }

    @Test
    void amountComesFromTheSnapshotAndRowIsCommittedBeforeCharging() {
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 250_000L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(provider.charge(500L, 42L, 250_000L)).thenReturn(new FakeProviderClient.ChargeResponse("bank_1", "PROCESSING"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });

        Payment payment = service.createPayment(1L, 42L, "k");

        assertEquals(250_000L, payment.getAmount());
        assertEquals(Payment.Status.PENDING, payment.getStatus(), "settled later by the webhook");
        assertEquals("bank_1", payment.getProviderRef());
        verify(provider).charge(500L, 42L, 250_000L);
    }

    @Test
    void providerOutageMarksPaymentFailedAndEmitsEvent() {
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(provider.charge(anyLong(), anyLong(), anyLong())).thenThrow(new RuntimeException("circuit open"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });

        Payment payment = service.createPayment(1L, 42L, "k");

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_FAILED),
                argThat(p -> "provider unavailable".equals(p.get("reason"))));
    }

    @Test
    void losingTheInsertRaceReturnsTheWinnersPayment() {
        Payment winner = new Payment(1L, 42L, 100L, "k");
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty(), Optional.of(winner));
        when(snapshotRepository.findById(42L)).thenReturn(Optional.of(reservedOrder(1L, 100L)));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertSame(winner, service.createPayment(1L, 42L, "k"));
        verifyNoInteractions(provider);
    }

    // ---- webhook

    @Test
    void webhookSettlesOnceAndEmitsPaymentSucceeded() {
        Payment payment = new Payment(1L, 42L, 100L, "k");
        ReflectionTestUtils.setField(payment, "id", 500L);
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", true);
        service.handleProviderResult(500L, "bank_1", true); // duplicate webhook

        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void declinedWebhookEmitsPaymentFailed() {
        Payment payment = new Payment(1L, 42L, 100L, "k");
        ReflectionTestUtils.setField(payment, "id", 500L);
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(payment));

        service.handleProviderResult(500L, "bank_1", false);

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_FAILED), any());
    }

    // ---- refunds

    private Payment succeededPayment() {
        Payment payment = new Payment(1L, 42L, 100L, "k");
        ReflectionTestUtils.setField(payment, "id", 500L);
        payment.setProviderRef("bank_1");
        payment.succeed();
        return payment;
    }

    @Test
    void cancelledPaidOrderIsRefundedOnce() {
        Payment payment = succeededPayment();
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(payment));
        when(refundRepository.existsByPaymentIdAndStatus(500L, Refund.Status.PENDING)).thenReturn(false);
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refundByOrderId(42L, "customer cancel after payment");

        assertEquals(Payment.Status.REFUNDED, payment.getStatus());
        verify(provider).refund("bank_1", 100L);
        verify(outbox).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_REFUNDED),
                argThat(p -> "customer cancel after payment".equals(p.get("reason"))));

        // second OrderCancelled (e.g. late-payment path): payment is no longer SUCCEEDED -> nothing
        service.refundByOrderId(42L, "again");
        verify(provider, times(1)).refund(any(), anyLong());
    }

    @Test
    void unpaidOrderHasNothingToRefund() {
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(new Payment(1L, 42L, 100L, "k")));
        service.refundByOrderId(42L, "x");
        verifyNoInteractions(provider, refundRepository);
    }

    @Test
    void adminCanOnlyRefundSucceededPayments() {
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(new Payment(1L, 42L, 100L, "k")));
        assertThrows(ConflictException.class, () -> service.refundByPaymentId(500L, "admin refund"));
    }

    @Test
    void failedProviderRefundStaysPendingForRetryThenGivesUp() {
        Payment payment = succeededPayment();
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(payment));
        when(refundRepository.existsByPaymentIdAndStatus(500L, Refund.Status.PENDING)).thenReturn(false);
        Refund[] created = new Refund[1];
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> created[0] = inv.getArgument(0));
        when(provider.refund(any(), anyLong())).thenThrow(new RuntimeException("bank down"));

        service.refundByOrderId(42L, "customer cancel");

        assertTrue(created[0].isPending());
        assertEquals(1, created[0].getAttempts());
        assertEquals("bank down", created[0].getLastError());
        assertEquals(Payment.Status.SUCCEEDED, payment.getStatus(), "not refunded yet");
        verify(outbox, never()).append(any(), any(), eq(EventTypes.PAYMENT_REFUNDED), any());

        // retry job: attempt 2 fails, attempt 3 (max) fails -> FAILED for a human
        when(refundRepository.findByStatusAndCreatedAtBefore(eq(Refund.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(created[0]));
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(payment));
        assertEquals(1, service.retryPendingRefunds());
        assertTrue(created[0].isPending());
        assertEquals(1, service.retryPendingRefunds());
        assertEquals(Refund.Status.FAILED, created[0].getStatus());
        assertEquals(3, created[0].getAttempts());
    }

    @Test
    void retrySucceedsLaterAndEmitsExactlyOneRefundEvent() {
        Payment payment = succeededPayment();
        Refund pending = new Refund(500L, 100L, "customer cancel");
        pending.recordAttempt();
        when(refundRepository.findByStatusAndCreatedAtBefore(eq(Refund.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pending));
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(payment));

        service.retryPendingRefunds();

        assertEquals(Refund.Status.SUCCEEDED, pending.getStatus());
        assertEquals(Payment.Status.REFUNDED, payment.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_PAYMENT_EVENTS), eq("42"), eq(EventTypes.PAYMENT_REFUNDED), any());
    }

    @Test
    void duplicatePendingRefundIsSettledWithoutCallingTheBank() {
        Payment payment = succeededPayment();
        payment.refund(); // another refund row already did it
        Refund pending = new Refund(500L, 100L, "x");
        when(refundRepository.findByStatusAndCreatedAtBefore(eq(Refund.Status.PENDING), any(Instant.class)))
                .thenReturn(List.of(pending));
        when(paymentRepository.findById(500L)).thenReturn(Optional.of(payment));

        service.retryPendingRefunds();

        assertEquals(Refund.Status.SUCCEEDED, pending.getStatus());
        verifyNoInteractions(provider, outbox);
    }

    // ---- snapshot (ADR 0011)

    @Test
    void snapshotBecomesPayableWhateverTheEventOrder() {
        OrderSnapshot[] store = new OrderSnapshot[1];
        when(snapshotRepository.findById(42L)).thenAnswer(inv -> Optional.ofNullable(store[0]));
        when(snapshotRepository.save(any(OrderSnapshot.class))).thenAnswer(inv -> store[0] = inv.getArgument(0));

        service.markOrderReserved(42L);            // StockReserved overtook OrderCreated
        assertFalse(store[0].isPayable(), "owner and amount unknown yet");
        service.rememberOrder(42L, 1L, 100L);
        assertTrue(store[0].isPayable());

        service.markOrderCancelled(42L);
        assertFalse(store[0].isPayable());
        service.markOrderReserved(42L);            // nothing revives a cancelled order
        assertEquals(OrderSnapshot.Status.CANCELLED, store[0].getStatus());
    }
}
