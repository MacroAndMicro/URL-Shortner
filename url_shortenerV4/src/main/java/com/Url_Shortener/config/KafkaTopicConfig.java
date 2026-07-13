package com.Url_Shortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * ============================================================
 * KAFKA INTEGRATION: Kafka Topic Configuration
 * ============================================================
 *
 * Auto-creates Kafka topics on startup via KafkaAdmin.
 * Topics are only created if they don't already exist.
 *
 * Topics:
 *   analytics-events     — Main event topic (3 partitions)
 *   analytics-events-dlt — Dead Letter Topic (1 partition)
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic analyticsEventsTopic() {
        return TopicBuilder.name("analytics-events")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic analyticsEventsDltTopic() {
        return TopicBuilder.name("analytics-events-dlt")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
