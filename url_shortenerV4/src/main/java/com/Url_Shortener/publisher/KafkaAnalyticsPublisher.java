package com.Url_Shortener.publisher;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * ============================================================
 * KAFKA INTEGRATION: Kafka Analytics Publisher
 * ============================================================
 *
 * Implements AnalyticsPublisher using KafkaTemplate.
 * This is the ONLY class in the application that knows about
 * Kafka producer specifics. Business services never see
 * KafkaTemplate or KafkaProducer.
 *
 * ERROR HANDLING:
 * Fire-and-forget — all exceptions are caught and logged.
 * The redirect must return 302 immediately regardless of
 * whether Kafka is available. If Kafka is down, events are
 * lost (acceptable for analytics; the trade-off is redirect
 * latency vs. analytics completeness).
 *
 * STRUCTURED LOGGING:
 * Logs eventId on publish for traceability across the
 * producer → Kafka → consumer pipeline.
 */
@Service
public class KafkaAnalyticsPublisher implements AnalyticsPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaAnalyticsPublisher.class);

    private static final String TOPIC = "analytics-events";

    private final KafkaTemplate<String, ClickEventCreatedEvent> kafkaTemplate;
    private final UrlShortenerMetrics metrics;

    public KafkaAnalyticsPublisher(KafkaTemplate<String, ClickEventCreatedEvent> kafkaTemplate,
                                    UrlShortenerMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    // OLD POSTGRES IMPLEMENTATION
    // ClickEvent clickEvent = new ClickEvent();
    // clickEvent.setClickDate(LocalDateTime.now());
    // ...
    // clickEventRepository.save(clickEvent);

    // NEW KAFKA IMPLEMENTATION
    @Override
    public void publish(ClickEventCreatedEvent event) {
        var sample = metrics.startTimer();
        try {
            kafkaTemplate.send(TOPIC, event.getShortUrl(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Event publish FAILED to Kafka: eventId={}, shortUrl={}, error={}",
                                    event.getEventId(), event.getShortUrl(), ex.getMessage());
                        } else {
                            log.debug("Event published to Kafka: eventId={}, shortUrl={}, topic={}, partition={}, offset={}",
                                    event.getEventId(), event.getShortUrl(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
            log.debug("Event sent to Kafka: eventId={}, shortUrl={}", event.getEventId(), event.getShortUrl());
            metrics.incrementKafkaPublished();
            metrics.stopKafkaPublishTime(sample);
        } catch (Exception e) {
            // FIRE-AND-FORGET: Never throw to caller.
            // The redirect must not be delayed by Kafka failures.
            log.warn("Failed to publish event to Kafka: eventId={}, shortUrl={}, error={}",
                    event.getEventId(), event.getShortUrl(), e.getMessage());
        }
    }
}
