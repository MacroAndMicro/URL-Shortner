package com.Url_Shortener.consumer;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.storage.AnalyticsStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * ============================================================
 * KAFKA INTEGRATION: Kafka Analytics Consumer
 * ============================================================
 *
 * Consumes ClickEventCreatedEvent messages from the
 * analytics-events Kafka topic, stores them in MongoDB via the
 * AnalyticsStorage abstraction, and updates the click count in
 * DynamoDB.
 *
 * IDEMPOTENCY:
 * Redelivered events are detected by eventId and skipped, so they
 * are not counted twice. If the DynamoDB increment fails after the
 * MongoDB write, the retry sees a duplicate and skips the increment:
 * clickCount can undercount, and MongoDB remains the accurate record.
 *
 * RETRY & DLT:
 * Configured via KafkaConfig (CommonErrorHandler with
 * DeadLetterPublishingRecoverer). After 3 retries with
 * exponential backoff, failed messages go to
 * analytics-events-dlt.
 */
@Component
public class KafkaAnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaAnalyticsConsumer.class);

    private final AnalyticsStorage analyticsStorage;
    private final UrlMappingDynamoRepository urlMappingRepository;
    private final UrlShortenerMetrics metrics;

    public KafkaAnalyticsConsumer(AnalyticsStorage analyticsStorage,
                                   UrlMappingDynamoRepository urlMappingRepository,
                                   UrlShortenerMetrics metrics) {
        this.analyticsStorage = analyticsStorage;
        this.urlMappingRepository = urlMappingRepository;
        this.metrics = metrics;
    }

    /**
     * Listens for click events on the analytics-events topic.
     * Manual acknowledgment — committed after successful persistence.
     */
    @KafkaListener(
            topics = "analytics-events",
            groupId = "analytics-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ClickEventCreatedEvent event, Acknowledgment acknowledgment) {
        log.debug("Event consumed from Kafka: eventId={}, shortUrl={}",
                event.getEventId(), event.getShortUrl());
        metrics.incrementKafkaConsumed();

        try {
            boolean stored = analyticsStorage.saveClickEvent(
                    event.getEventId(),
                    event.getShortUrl(),
                    LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault()),
                    event.getIpAddress(),
                    event.getReferrer(),
                    event.getUserAgent()
            );

            if (stored) {
                metrics.incrementDynamoWrite();
                urlMappingRepository.incrementClickCount(event.getShortUrl());
            }

            // Manual acknowledgment after successful persistence
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Let Spring Kafka ErrorHandler manage retries and DLT routing.
            log.error("Failed to persist event: eventId={}, shortUrl={}, error={}",
                    event.getEventId(), event.getShortUrl(), e.getMessage());
            throw e;
        }
    }
}
