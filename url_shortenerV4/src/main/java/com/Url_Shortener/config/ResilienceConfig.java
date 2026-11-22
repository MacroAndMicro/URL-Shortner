package com.Url_Shortener.config;

import com.Url_Shortener.resilience.Breakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * One circuit breaker per outside service (see Breakers for the names).
 *
 * A breaker watches the last window-size calls. Once at least minimum-calls
 * have been made, if failure-rate-percent of them failed, or were slower
 * than the slow-call threshold, it opens: calls fail immediately for
 * open-duration instead of waiting on timeouts. It then lets
 * half-open-calls through as a trial and closes again if they succeed.
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            MeterRegistry meterRegistry,
            @Value("${app.circuit-breaker.window-size:20}") int windowSize,
            @Value("${app.circuit-breaker.minimum-calls:10}") int minimumCalls,
            @Value("${app.circuit-breaker.failure-rate-percent:50}") float failureRate,
            @Value("${app.circuit-breaker.open-duration:30s}") Duration openDuration,
            @Value("${app.circuit-breaker.half-open-calls:3}") int halfOpenCalls,
            @Value("${app.circuit-breaker.slow-call:2s}") Duration slowCall,
            @Value("${app.circuit-breaker.redis-slow-call:300ms}") Duration redisSlowCall,
            @Value("${app.circuit-breaker.kafka-slow-call:500ms}") Duration kafkaSlowCall) {

        CircuitBreakerConfig base = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .slowCallRateThreshold(failureRate)
                .slowCallDurationThreshold(slowCall)
                .waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                // Without this a breaker nobody calls (e.g. the paused Kafka consumer's)
                // would stay open forever.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(base);
        create(registry, base, Breakers.DYNAMODB, slowCall, Breakers::isDynamoDbOutage);
        create(registry, base, Breakers.MONGODB, slowCall, Breakers::isMongoDbOutage);
        create(registry, base, Breakers.REDIS, redisSlowCall, Breakers::isRedisOutage);
        create(registry, base, Breakers.KAFKA, kafkaSlowCall, Breakers::isKafkaOutage);

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    private static void create(CircuitBreakerRegistry registry, CircuitBreakerConfig base, String name,
                               Duration slowCall, Predicate<Throwable> countsAsFailure) {
        CircuitBreakerConfig config = CircuitBreakerConfig.from(base)
                .slowCallDurationThreshold(slowCall)
                .recordException(countsAsFailure)
                .build();
        CircuitBreaker breaker = registry.circuitBreaker(name, config);
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker '{}' {}", name, event.getStateTransition()));
    }
}
