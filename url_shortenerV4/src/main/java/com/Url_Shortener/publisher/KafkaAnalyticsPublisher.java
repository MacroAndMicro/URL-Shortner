package com.Url_Shortener.publisher;

import com.Url_Shortener.resilience.Breakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
 * Fire-and-forget — all exceptions are caught, logged and counted.
 * If Kafka is down, events are lost (acceptable for analytics; the
 * trade-off is redirect latency vs. analytics completeness). A send
 * can block the caller for up to max.block.ms, set in KafkaConfig, so
 * sends go through the "kafka" circuit breaker: once Kafka is found to
 * be down, events are skipped at once (counted in
 * kafka.events.publish.skipped.count) and redirects are not slowed.
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
    private final CircuitBreaker breaker;

    public KafkaAnalyticsPublisher(KafkaTemplate<String, ClickEventCreatedEvent> kafkaTemplate,
                                    UrlShortenerMetrics metrics,
                                    CircuitBreakerRegistry breakers) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.breaker = breakers.circuitBreaker(Breakers.KAFKA);
    }

    @Override
    public void publish(ClickEventCreatedEvent event) {
        if (!breaker.tryAcquirePermission()) {
            metrics.incrementKafkaPublishSkipped();
            log.debug("Kafka circuit breaker open, click event skipped: eventId={}", event.getEventId());
            return;
        }
        // The send completes later, so the outcome is reported to the breaker
        // from the callback rather than by wrapping the call.
        long start = breaker.getCurrentTimestamp();
        var sample = metrics.startTimer();
        try {
            kafkaTemplate.send(TOPIC, event.getShortUrl(), event)
                    .whenComplete((result, ex) -> {
                        long took = breaker.getCurrentTimestamp() - start;
                        if (ex != null) {
                            breaker.onError(took, breaker.getTimestampUnit(), ex);
                            metrics.incrementKafkaPublishFailure();
                            log.warn("Event publish FAILED to Kafka: eventId={}, shortUrl={}, error={}",
                                    event.getEventId(), event.getShortUrl(), ex.getMessage());
                        } else {
                            breaker.onSuccess(took, breaker.getTimestampUnit());
                            metrics.incrementKafkaPublished();
                            metrics.stopKafkaPublishTime(sample);
                            log.debug("Event published to Kafka: eventId={}, shortUrl={}, topic={}, partition={}, offset={}",
                                    event.getEventId(), event.getShortUrl(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            breaker.onError(breaker.getCurrentTimestamp() - start, breaker.getTimestampUnit(), e);
            // FIRE-AND-FORGET: Never throw to caller.
            metrics.incrementKafkaPublishFailure();
            log.warn("Failed to publish event to Kafka: eventId={}, shortUrl={}, error={}",
                    event.getEventId(), event.getShortUrl(), e.getMessage());
        }
    }
}
