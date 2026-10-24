package com.shopflow.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
 * {@link Propagation#MANDATORY}: called outside a transaction the mark would
 * commit on its own, before the business change — a crash in between would
 * then lose the event as a "duplicate". Failing fast makes that bug visible.
 */
@Service
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public IdempotencyGuard(ProcessedEventRepository repository,
                            @Value("${shopflow.processed-events.retention:14d}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    /** @return true if this is the first time we see the event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId) {
        return repository.insertIfAbsent(eventId) == 1;
    }

    /**
     * Keeps processed_events from growing forever. The retention must stay
     * longer than the Kafka topic retention (7 days by default): an event can
     * only be redelivered while it still exists in the topic.
     */
    @Scheduled(fixedDelayString = "${shopflow.processed-events.cleanup-poll-ms:3600000}", initialDelay = 90000)
    @Transactional
    public void deleteOldMarks() {
        int deleted = repository.deleteProcessedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Deleted {} processed_events mark(s) older than {}", deleted, retention);
        }
    }
}
