package com.shopflow.inventory.repository;

import com.shopflow.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, Long> {
}
