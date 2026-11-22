package com.Url_Shortener.consumer;

import com.Url_Shortener.resilience.Breakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Pauses the click-event consumer while MongoDB is down, so events wait in
 * Kafka instead of being retried into the dead-letter topic.
 *
 * When the "mongodb" breaker opens, the consumer is paused. When it moves to
 * half-open (automatically, after the open duration) the consumer resumes;
 * its next events are the breaker's trial calls. If MongoDB is still down
 * the breaker opens again and the consumer pauses again.
 */
@Component
public class AnalyticsConsumerPauser {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConsumerPauser.class);

    private final KafkaListenerEndpointRegistry listeners;

    public AnalyticsConsumerPauser(CircuitBreakerRegistry breakers, KafkaListenerEndpointRegistry listeners) {
        this.listeners = listeners;
        breakers.circuitBreaker(Breakers.MONGODB).getEventPublisher()
                .onStateTransition(event -> onStateChange(event.getStateTransition().getToState()));
    }

    void onStateChange(CircuitBreaker.State state) {
        MessageListenerContainer consumer = listeners.getListenerContainer(KafkaAnalyticsConsumer.LISTENER_ID);
        if (consumer == null) {
            return;
        }
        switch (state) {
            case OPEN, FORCED_OPEN -> {
                consumer.pause();
                log.warn("MongoDB unavailable: click-event consumer paused; events wait in Kafka");
            }
            case HALF_OPEN, CLOSED -> {
                if (consumer.isPauseRequested()) {
                    consumer.resume();
                    log.info("Click-event consumer resumed (MongoDB breaker {})", state);
                }
            }
            default -> {
                // DISABLED / METRICS_ONLY are not used.
            }
        }
    }
}
