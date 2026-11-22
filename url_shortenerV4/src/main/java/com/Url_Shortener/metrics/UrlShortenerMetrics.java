package com.Url_Shortener.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================
 * OBSERVABILITY: Centralized Business Metrics
 * ============================================================
 *
 * All custom counters, timers, and gauges are registered here.
 * Services inject this component and call its methods at the
 * appropriate points. This keeps metrics registration centralized
 * and avoids scattering MeterRegistry calls across the codebase.
 *
 * Micrometer naming conventions:
 *   - Counters auto-append _total
 *   - Timers auto-append _seconds
 *   - Dot-separated, lowercase metric names
 */
@Component
public class UrlShortenerMetrics {

    private final MeterRegistry registry;

    // ============================================================
    // COUNTERS
    // ============================================================
    private final Counter urlCreationCount;
    private final Counter urlDeletionCount;
    private final Counter redirectRequests;
    private final Counter redirectSuccess;
    private final Counter redirectFailure;
    private final Counter authLoginSuccess;
    private final Counter authLoginFailure;
    private final Counter authRefreshCount;
    private final Counter analyticsRequests;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter kafkaEventsPublished;
    private final Counter kafkaPublishFailures;
    private final Counter kafkaPublishSkipped;
    private final Counter kafkaEventsConsumed;
    private final Counter kafkaEventsDeadLetter;
    private final Counter mongoWrites;
    private final Counter mongoWriteFailures;
    private final Counter dynamoReads;
    private final Counter dynamoWrites;

    // ============================================================
    // TIMERS
    // ============================================================
    private final Timer urlCreationTime;
    private final Timer redirectTotalTime;
    private final Timer redisLookupTime;
    private final Timer dynamoLookupTime;
    private final Timer kafkaPublishTime;
    private final Timer mongoPersistTime;
    private final Timer analyticsQueryTime;

    public UrlShortenerMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Counters
        this.urlCreationCount = Counter.builder("url.creation.count")
                .description("Number of URLs created")
                .register(registry);

        this.urlDeletionCount = Counter.builder("url.deletion.count")
                .description("Number of URLs deleted")
                .register(registry);

        this.redirectRequests = Counter.builder("redirect.requests.count")
                .description("Total redirect requests")
                .register(registry);

        this.redirectSuccess = Counter.builder("redirect.success.count")
                .description("Successful redirects (URL found)")
                .register(registry);

        this.redirectFailure = Counter.builder("redirect.failure.count")
                .description("Failed redirects (URL not found)")
                .register(registry);

        this.authLoginSuccess = Counter.builder("auth.login.success.count")
                .description("Successful JWT logins")
                .register(registry);

        this.authLoginFailure = Counter.builder("auth.login.failure.count")
                .description("Failed JWT logins")
                .register(registry);

        this.authRefreshCount = Counter.builder("auth.refresh.count")
                .description("Refresh token requests")
                .register(registry);

        this.analyticsRequests = Counter.builder("analytics.requests.count")
                .description("Analytics/dashboard API requests")
                .register(registry);

        this.cacheHits = Counter.builder("cache.hits.count")
                .description("Redis cache hits")
                .register(registry);

        this.cacheMisses = Counter.builder("cache.misses.count")
                .description("Redis cache misses")
                .register(registry);

        this.kafkaEventsPublished = Counter.builder("kafka.events.published.count")
                .description("Kafka events published")
                .register(registry);

        this.kafkaPublishFailures = Counter.builder("kafka.events.publish.failures.count")
                .description("Kafka events that failed to publish")
                .register(registry);
        this.kafkaPublishSkipped = Counter.builder("kafka.events.publish.skipped.count")
                .description("Click events not sent because the Kafka circuit breaker was open")
                .register(registry);

        this.kafkaEventsConsumed = Counter.builder("kafka.events.consumed.count")
                .description("Kafka events consumed")
                .register(registry);

        this.kafkaEventsDeadLetter = Counter.builder("kafka.events.deadletter.count")
                .description("Kafka events sent to DLT")
                .register(registry);

        this.mongoWrites = Counter.builder("mongo.writes.count")
                .description("MongoDB write operations")
                .register(registry);

        this.mongoWriteFailures = Counter.builder("mongo.write.failures.count")
                .description("MongoDB write failures")
                .register(registry);

        this.dynamoReads = Counter.builder("dynamodb.reads.count")
                .description("DynamoDB read operations")
                .register(registry);

        this.dynamoWrites = Counter.builder("dynamodb.writes.count")
                .description("DynamoDB write operations")
                .register(registry);

        // Timers
        this.urlCreationTime = histogramTimer("url.creation.time", "Time to create a short URL");
        this.redirectTotalTime = histogramTimer("redirect.total.time", "Total redirect response time");
        this.redisLookupTime = histogramTimer("redis.lookup.time", "Redis cache lookup time");
        this.dynamoLookupTime = histogramTimer("dynamodb.lookup.time", "DynamoDB lookup time");
        this.kafkaPublishTime = histogramTimer("kafka.publish.time", "Kafka event publish time");
        this.mongoPersistTime = histogramTimer("mongo.persist.time", "MongoDB document persist time");
        this.analyticsQueryTime = histogramTimer("analytics.query.time", "Analytics query execution time");
    }

    // Histogram buckets are required by the Grafana histogram_quantile panels.
    private Timer histogramTimer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    // ============================================================
    // COUNTER METHODS
    // ============================================================

    public void incrementUrlCreation() { urlCreationCount.increment(); }
    public void incrementUrlDeletion() { urlDeletionCount.increment(); }
    public void incrementRedirectRequest() { redirectRequests.increment(); }
    public void incrementRedirectSuccess() { redirectSuccess.increment(); }
    public void incrementRedirectFailure() { redirectFailure.increment(); }
    public void incrementAuthLoginSuccess() { authLoginSuccess.increment(); }
    public void incrementAuthLoginFailure() { authLoginFailure.increment(); }
    public void incrementAuthRefresh() { authRefreshCount.increment(); }
    public void incrementAnalyticsRequest() { analyticsRequests.increment(); }
    public void incrementCacheHit() { cacheHits.increment(); }
    public void incrementCacheMiss() { cacheMisses.increment(); }
    public void incrementKafkaPublished() { kafkaEventsPublished.increment(); }
    public void incrementKafkaPublishFailure() { kafkaPublishFailures.increment(); }
    public void incrementKafkaPublishSkipped() { kafkaPublishSkipped.increment(); }
    public void incrementKafkaConsumed() { kafkaEventsConsumed.increment(); }
    public void incrementKafkaDeadLetter() { kafkaEventsDeadLetter.increment(); }
    public void incrementMongoWrite() { mongoWrites.increment(); }
    public void incrementMongoWriteFailure() { mongoWriteFailures.increment(); }
    public void incrementDynamoRead() { dynamoReads.increment(); }
    public void incrementDynamoWrite() { dynamoWrites.increment(); }

    // ============================================================
    // TIMER METHODS
    // ============================================================

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopUrlCreationTime(Timer.Sample sample) { sample.stop(urlCreationTime); }
    public void stopRedirectTotalTime(Timer.Sample sample) { sample.stop(redirectTotalTime); }
    public void stopRedisLookupTime(Timer.Sample sample) { sample.stop(redisLookupTime); }
    public void stopDynamoLookupTime(Timer.Sample sample) { sample.stop(dynamoLookupTime); }
    public void stopKafkaPublishTime(Timer.Sample sample) { sample.stop(kafkaPublishTime); }
    public void stopMongoPersistTime(Timer.Sample sample) { sample.stop(mongoPersistTime); }
    public void stopAnalyticsQueryTime(Timer.Sample sample) { sample.stop(analyticsQueryTime); }

    /**
     * Records a duration directly from nanoseconds.
     */
    public void recordRedisLookup(long durationNanos) {
        redisLookupTime.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
