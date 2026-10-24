package com.shopflow.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small job that moves unsent outbox rows to Kafka (NFR-08).
 * Kafka key = aggregateId (orderId) so events for one order stay ordered.
 *
 * Guarantees:
 * <ul>
 *   <li><b>One publisher per service at a time.</b> With two replicas both
 *       ticking, each would read the same unsent rows and send them twice — and
 *       worse, could interleave them so OrderCancelled overtakes OrderCreated.
 *       A transaction-scoped advisory lock lets exactly one replica publish per
 *       tick; the others skip without waiting. ({@code FOR UPDATE SKIP LOCKED}
 *       would avoid the duplicates but still lets two replicas publish two
 *       halves of one order's events in parallel, i.e. out of order.)</li>
 *   <li><b>At-least-once.</b> A row is marked sent only after the broker acked
 *       it; a crash between the ack and the commit re-sends it, which the
 *       consumers' processed_events check (NFR-09) absorbs.</li>
 *   <li><b>Order kept on failure.</b> The batch stops at the first failed
 *       send; rows already acked are committed, the rest wait for the next tick.</li>
 * </ul>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Arbitrary but fixed advisory-lock key for "the outbox publisher of this database". */
    static final long PUBLISHER_LOCK_KEY = 0x5348_4F50_4F55_5458L; // "SHOPOUTX"

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration retention;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${shopflow.outbox.retention:7d}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${shopflow.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        if (!outboxRepository.tryAdvisoryXactLock(PUBLISHER_LOCK_KEY)) {
            return; // another replica is publishing right now
        }
        List<OutboxEvent> batch = outboxRepository.findTop50BySentAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                event.markSent();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Outbox send failed for {} ({}), will retry", event.getId(), event.getEventType(), e);
                return; // keep order: stop the batch, retry next tick
            }
        }
    }

    /** Sent rows are not needed for correctness; keep a week for debugging, then drop them. */
    @Scheduled(fixedDelayString = "${shopflow.outbox.cleanup-poll-ms:3600000}", initialDelay = 60000)
    @Transactional
    public void deleteOldSentRows() {
        int deleted = outboxRepository.deleteSentBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Deleted {} sent outbox row(s) older than {}", deleted, retention);
        }
    }
}
