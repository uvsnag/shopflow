package com.shopflow.notification.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * scanBasePackages on the application class only widens component scanning;
 * entity and repository scanning must be widened too or the shared
 * outbox/processed_events JPA pieces in libs/common are never registered.
 * Kept in its own @Configuration (not on the application class) so that
 * \@WebMvcTest slices, which have no JPA, do not try to create repositories.
 */
@Configuration
@EntityScan(basePackages = "com.shopflow")
@EnableJpaRepositories(basePackages = "com.shopflow")
public class JpaConfig {
}
