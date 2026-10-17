package com.shopflow.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.shopflow")
// scanBasePackages only widens component scanning; entity and repository
// scanning must be widened too or the shared outbox/processed_events JPA
// pieces in libs/common are never registered and startup fails.
@EntityScan(basePackages = "com.shopflow")
@EnableJpaRepositories(basePackages = "com.shopflow")
@EnableScheduling
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
