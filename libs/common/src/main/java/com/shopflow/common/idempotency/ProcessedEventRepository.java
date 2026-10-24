package com.shopflow.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Atomic "claim" of an eventId: one statement, arbitrated by the primary key.
     * Two consumers racing on the same id cannot both get 1 back — the second
     * insert waits for the first transaction and then hits the conflict, which
     * PostgreSQL turns into 0 rows instead of an error. No exception means the
     * caller's transaction stays usable (a failed INSERT would abort it).
     *
     * @return 1 if this call claimed the id, 0 if it was already there
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, processed_at)
            VALUES (:eventId, now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId);

    /** Housekeeping: marks older than any possible redelivery are dropped. */
    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
