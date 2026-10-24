package com.shopflow.inventory.repository;

import com.shopflow.inventory.domain.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * BR-01: {@code SELECT ... FOR UPDATE} on every stock row the caller is
     * about to change. Concurrent orders for the same product queue up on the
     * row lock instead of failing on @Version and burning Kafka retries (a hot
     * product would otherwise push orders to the DLT). Rows are always locked
     * in product id order, so two orders that share several products can
     * never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId in :ids order by s.productId")
    List<Stock> lockAllByIds(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :id")
    Optional<Stock> lockById(@Param("id") Long id);

    /** FR-13: create the row if missing without racing another admin request on the primary key. */
    @Modifying
    @Query(value = "INSERT INTO stock (product_id, available) VALUES (:id, 0) ON CONFLICT (product_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") Long id);
}
