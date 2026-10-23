package com.shopflow.common.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent consumer (NFR-09). Usage in a Kafka listener:
 *
 * <pre>
 * if (!guard.markProcessed(envelope.eventId())) return; // duplicate, skip
 * </pre>
 *
 * The mark is written with a single {@code INSERT ... ON CONFLICT DO NOTHING}
 * inside the caller's transaction, so:
 * <ul>
 *   <li>there is no check-then-insert window: two deliveries of the same event
 *       on two consumer threads cannot both see "not processed yet";</li>
 *   <li>a duplicate produces no exception, so the enclosing PostgreSQL
 *       transaction is not aborted and nothing is marked rollback-only;</li>
 *   <li>the mark commits or rolls back together with the business change —
 *       a failed handler is retried, never skipped as a false duplicate.</li>
 * </ul>
 */
@Service
public class IdempotencyGuard {

    private final ProcessedEventRepository repository;

    public IdempotencyGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /** @return true if this is the first time we see the event. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean markProcessed(UUID eventId) {
        return repository.insertIfAbsent(eventId) == 1;
    }
}
