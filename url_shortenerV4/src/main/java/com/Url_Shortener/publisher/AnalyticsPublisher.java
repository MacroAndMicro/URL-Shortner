package com.Url_Shortener.publisher;

import com.Url_Shortener.event.ClickEventCreatedEvent;

/**
 * ============================================================
 * KAFKA INTEGRATION: Analytics Publisher Interface (Port)
 * ============================================================
 *
 * Abstraction for publishing analytics events. Business services
 * depend on this interface, NOT on KafkaTemplate or any
 * Kafka-specific implementation.
 *
 * Following the same pattern as:
 *   - CacheService (Redis abstraction)
 *   - AnalyticsStorage (MongoDB abstraction)
 *
 * FUTURE COMPATIBILITY:
 * The publish method is fire-and-forget. Implementations must
 * never throw to the caller — the redirect must return 302
 * immediately regardless of what happens downstream.
 */
public interface AnalyticsPublisher {

    /**
     * Publishes a click event for asynchronous processing.
     * Fire-and-forget — implementations must catch all exceptions.
     *
     * @param event the click event to publish
     */
    void publish(ClickEventCreatedEvent event);
}
