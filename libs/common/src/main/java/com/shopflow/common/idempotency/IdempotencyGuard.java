package com.shopflow.common.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Usage in a Kafka listener:
 *
 * <pre>
 * if (!guard.markProcessed(envelope.eventId())) return; // duplicate, skip
 * </pre>
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
        if (repository.existsById(eventId)) {
            return false;
        }
        try {
            repository.saveAndFlush(new ProcessedEvent(eventId));
            return true;
        } catch (DataIntegrityViolationException raceLost) {
            return false;
        }
    }
}
