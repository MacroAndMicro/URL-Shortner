package com.Url_Shortener.config;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================
 * KAFKA INTEGRATION: Kafka Configuration
 * ============================================================
 *
 * Configures Kafka producer, consumer, retry, and Dead Letter
 * Topic (DLT) handling. These beans replace Spring Boot's
 * auto-configured ones, so only spring.kafka.bootstrap-servers
 * is read from application.yaml.
 *
 * PRODUCER:
 *   - JSON serialization (String keys, ClickEventCreatedEvent values)
 *   - acks=all for durability
 *   - Idempotent producer
 *   - Batching enabled for throughput
 *   - Sends block for at most 1 second, so an unavailable broker
 *     cannot stall redirects for long
 *
 * CONSUMER:
 *   - JSON deserialization; malformed records go straight to the DLT
 *   - Consumer group: analytics-consumer
 *   - Manual acknowledgment
 *   - Trusted packages: com.Url_Shortener.event
 *
 * RETRY & DLT:
 *   - Exponential backoff: 1s → 2s → 4s (3 retries after the first failure)
 *   - On exhaustion → Dead Letter Topic: analytics-events-dlt
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ============================================================
    // PRODUCER
    // ============================================================

    @Bean
    public ProducerFactory<String, ClickEventCreatedEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, ClickEventCreatedEvent> kafkaTemplate(
            ProducerFactory<String, ClickEventCreatedEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ============================================================
    // CONSUMER
    // ============================================================

    @Bean
    public ConsumerFactory<String, ClickEventCreatedEvent> consumerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "analytics-consumer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.Url_Shortener.event");
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                "com.Url_Shortener.event.ClickEventCreatedEvent");

        DefaultKafkaConsumerFactory<String, ClickEventCreatedEvent> factory = new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>())
        );
        // Publishes Kafka client metrics to Prometheus, including consumer lag.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ClickEventCreatedEvent>
    kafkaListenerContainerFactory(ConsumerFactory<String, ClickEventCreatedEvent> consumerFactory,
                                  CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, ClickEventCreatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // ============================================================
    // RETRY & DEAD LETTER TOPIC
    // ============================================================

    /**
     * Configures error handling with exponential backoff retry and DLT.
     *
     * Strategy:
     *   1. First failure  → retry after 1 second
     *   2. Second failure → retry after 2 seconds
     *   3. Third failure  → retry after 4 seconds
     *   4. All retries exhausted → publish to analytics-events-dlt
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, ClickEventCreatedEvent> kafkaTemplate,
                                                UrlShortenerMetrics metrics) {
        // Dead Letter Topic recoverer: sends failed messages to analytics-events-dlt
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // Exponential backoff: 1s initial, 2x multiplier, 10s max, 3 retries
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(10000L);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, ex) -> {
            recoverer.accept(record, ex);
            metrics.incrementKafkaDeadLetter();
        }, backOff);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                org.slf4j.LoggerFactory.getLogger(KafkaConfig.class).warn(
                        "Kafka retry attempt {} for event: key={}, error={}",
                        deliveryAttempt, record != null ? record.key() : "null",
                        ex.getMessage())
        );

        return errorHandler;
    }
}
