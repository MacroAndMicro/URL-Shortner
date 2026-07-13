package com.Url_Shortener.consumer;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.storage.AnalyticsStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * KAFKA INTEGRATION: Kafka Analytics Consumer
 * ============================================================
 *
 * Consumes ClickEventCreatedEvent messages from the
 * analytics-events Kafka topic and persists them to MongoDB
 * via the AnalyticsStorage abstraction.
 *
 * DESIGN PRINCIPLES:
 *   - Depends on AnalyticsStorage interface (NOT MongoRepository)
 *   - Converts ClickEventCreatedEvent → ClickEventDocument
 *   - No business logic — only translation + persistence
 *   - Structured logging at each step
 *
 * RETRY & DLT:
 * Configured via KafkaConfig (CommonErrorHandler with
 * DeadLetterPublishingRecoverer). After 3 retries with
 * exponential backoff, failed messages go to
 * analytics-events-dlt.
 *
 * OBSERVABILITY:
 *   Event consumed  → log.info
 *   MongoDB persisted → log.debug
 *   Retry attempt    → log.warn
 *   Dead letter      → log.error (handled by ErrorHandler)
 */
@Component
public class KafkaAnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaAnalyticsConsumer.class);

    private final AnalyticsStorage analyticsStorage;
    private final UrlShortenerMetrics metrics;

    public KafkaAnalyticsConsumer(AnalyticsStorage analyticsStorage,
                                   UrlShortenerMetrics metrics) {
        this.analyticsStorage = analyticsStorage;
        this.metrics = metrics;
    }

    /**
     * Listens for click events on the analytics-events topic.
     * Manual acknowledgment — committed after successful MongoDB write.
     */
    @KafkaListener(
            topics = "analytics-events",
            groupId = "analytics-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ClickEventCreatedEvent event, Acknowledgment acknowledgment) {
        log.info("Event consumed from Kafka: eventId={}, shortUrl={}, originalUrl={}",
                event.getEventId(), event.getShortUrl(), event.getOriginalUrl());
        metrics.incrementKafkaConsumed();

        try {
            // OLD POSTGRES IMPLEMENTATION
            // Direct JPA repository save in UrlMappingService

            // NEW KAFKA IMPLEMENTATION
            // Convert ClickEventCreatedEvent → AnalyticsStorage call
            analyticsStorage.saveClickEvent(
                    event.getShortUrl(),
                    event.getIpAddress(),
                    event.getReferrer(),
                    event.getUserAgent()
            );

            log.debug("Event persisted to MongoDB via AnalyticsStorage: eventId={}, shortUrl={}",
                    event.getEventId(), event.getShortUrl());

            // Manual acknowledgment after successful persistence
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Let Spring Kafka ErrorHandler manage retries and DLT routing.
            // This exception will trigger the retry/DLT pipeline configured
            // in KafkaConfig.
            log.error("Failed to persist event: eventId={}, shortUrl={}, error={}",
                    event.getEventId(), event.getShortUrl(), e.getMessage());
            throw e;
        }
    }
}
