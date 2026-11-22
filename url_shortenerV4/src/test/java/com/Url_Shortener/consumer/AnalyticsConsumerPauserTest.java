package com.Url_Shortener.consumer;

import com.Url_Shortener.resilience.Breakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.mockito.Mockito.*;

class AnalyticsConsumerPauserTest {

    private CircuitBreaker mongo;
    private MessageListenerContainer consumer;

    @BeforeEach
    void setUp() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        mongo = breakers.circuitBreaker(Breakers.MONGODB);
        consumer = mock(MessageListenerContainer.class);
        KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);
        when(listeners.getListenerContainer(KafkaAnalyticsConsumer.LISTENER_ID)).thenReturn(consumer);
        new AnalyticsConsumerPauser(breakers, listeners);
    }

    @Test
    void pausesWhenMongoDbBreakerOpens() {
        mongo.transitionToOpenState();

        verify(consumer).pause();
    }

    @Test
    void resumesForTheTrialCallsWhenHalfOpen() {
        mongo.transitionToOpenState();
        when(consumer.isPauseRequested()).thenReturn(true);

        mongo.transitionToHalfOpenState();

        verify(consumer).resume();
    }

    @Test
    void pausesAgainIfTheTrialFails() {
        mongo.transitionToOpenState();
        when(consumer.isPauseRequested()).thenReturn(true);
        mongo.transitionToHalfOpenState();

        mongo.transitionToOpenState();

        verify(consumer, times(2)).pause();
    }
}
