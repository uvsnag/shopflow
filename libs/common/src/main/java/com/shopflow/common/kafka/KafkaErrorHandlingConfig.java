package com.shopflow.common.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Shared by every Kafka consumer. Spring Boot picks this bean up automatically
 * for the auto-configured listener container factory.
 *
 * <ul>
 *   <li><b>Transient failures</b> (a lock conflict with a concurrent writer,
 *       the database restarting) are retried with exponential backoff:
 *       0.5s, 1s, 2s, 4s, 8s — about 15s in total, long enough for a short
 *       blip without stalling the partition for minutes.</li>
 *   <li><b>Poison messages</b> (unparseable JSON, a non-numeric id) can never
 *       succeed, so they skip the retries and go straight to the dead letter
 *       topic (e.g. order-events.DLT) instead of blocking the partition.</li>
 * </ul>
 * Every handler is idempotent (NFR-09), so a retry never applies a change twice.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // IllegalArgumentException covers EventMapper parse errors and NumberFormatException
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
