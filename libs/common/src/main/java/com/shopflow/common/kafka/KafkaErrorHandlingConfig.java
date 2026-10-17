package com.shopflow.common.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Shared by every Kafka consumer: a message that still fails after 3 attempts
 * is parked on the topic's dead letter topic (e.g. order-events.DLT) so it
 * doesn't block the partition. Spring Boot picks this bean up automatically
 * for the auto-configured listener container factory.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        // FixedBackOff(interval, maxRetries): 1 initial try + 2 retries = 3 attempts
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }
}
