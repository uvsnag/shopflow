package com.shopflow.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50BySentAtIsNullOrderByCreatedAtAsc();

    /**
     * Transaction-scoped PostgreSQL advisory lock: returns false at once (no
     * waiting) when another replica of this service holds it, and is released
     * automatically on commit / rollback. Advisory locks are per database, and
     * every service has its own database, so one constant key is enough.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryXactLock(@Param("key") long key);

    /** Housekeeping: sent rows are only kept for a while for debugging. */
    @Modifying
    @Query("delete from OutboxEvent e where e.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
