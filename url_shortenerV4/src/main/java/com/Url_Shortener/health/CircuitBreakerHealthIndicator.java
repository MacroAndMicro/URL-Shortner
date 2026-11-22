package com.Url_Shortener.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows each circuit breaker's state at /actuator/health under
 * "circuitBreakers". An open breaker is reported in the details but does
 * not mark the application DOWN: the app keeps serving what it can, and
 * the per-service indicators (dynamoDb, kafka, mongo, redis) already say
 * which service is down.
 */
@Component("circuitBreakers")
public class CircuitBreakerHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerHealthIndicator(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (CircuitBreaker breaker : registry.getAllCircuitBreakers()) {
            CircuitBreaker.Metrics m = breaker.getMetrics();
            details.put(breaker.getName(), Map.of(
                    "state", breaker.getState().name(),
                    "failureRatePercent", m.getFailureRate(),
                    "slowCallRatePercent", m.getSlowCallRate(),
                    "notPermittedCalls", m.getNumberOfNotPermittedCalls()));
        }
        return Health.up().withDetails(details).build();
    }
}
