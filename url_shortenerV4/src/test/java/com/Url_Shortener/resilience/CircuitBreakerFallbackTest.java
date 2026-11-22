package com.Url_Shortener.resilience;

import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.cache.RedisCacheService;
import com.Url_Shortener.config.ResilienceConfig;
import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.plan.GuestQuotaService;
import com.Url_Shortener.plan.OwnerStatus;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.publisher.KafkaAnalyticsPublisher;
import com.Url_Shortener.repository.ClickEventMongoRepository;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.storage.MongoAnalyticsStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Each protected call site, with its breaker forced open: the fallback is used
 * and the service is not called at all.
 */
class CircuitBreakerFallbackTest {

    private CircuitBreakerRegistry breakers;
    private UrlShortenerMetrics metrics;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        metrics = new UrlShortenerMetrics(meters);
        breakers = new ResilienceConfig().circuitBreakerRegistry(meters, 20, 10, 50f,
                Duration.ofSeconds(30), 3, Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofMillis(500));
    }

    private void open(String name) {
        breakers.circuitBreaker(name).transitionToOpenState();
    }

    // ============================================================
    // REDIS
    // ============================================================

    @Test
    void redisCacheFallsBackToAMissWithoutTouchingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisCacheService cache = new RedisCacheService(redis, new ObjectMapper(), breakers, 24);
        open(Breakers.REDIS);

        assertThat(cache.get("abc")).isEmpty();
        assertThat(cache.isMarkedMissing("abc")).isFalse();
        cache.put("abc", new CachedUrlData("abc", "https://x", 1L, "u", 0, "2026-11-21T10:00:00", null, null));
        cache.evict("abc");

        verifyNoInteractions(redis);
    }

    @Test
    void ownerPlanIsReadFromPostgresWhileRedisIsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.empty());
        PlanService plans = new PlanService(users, redis, Clock.systemUTC(), breakers);
        open(Breakers.REDIS);

        OwnerStatus status = plans.ownerStatus(7L);

        assertThat(status.effective()).isEqualTo(Plan.FREE);
        verify(users).findById(7L);
        verifyNoInteractions(redis);
    }

    @Test
    void guestLinksAreRefusedWhileRedisIsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GuestQuotaService quota = new GuestQuotaService(redis, new IpHasher("salt"), Clock.systemUTC(), 10, breakers);
        open(Breakers.REDIS);

        assertThatThrownBy(() -> quota.acquire("3f2b8c1e-9d4a-4b7e-8f21-6a5c0d9e1b23", "1.2.3.4"))
                .isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(redis);
    }

    // ============================================================
    // KAFKA
    // ============================================================

    @Test
    @SuppressWarnings("unchecked")
    void clickEventsAreSkippedWithoutBlockingWhileKafkaIsOpen() {
        KafkaTemplate<String, ClickEventCreatedEvent> kafka = mock(KafkaTemplate.class);
        KafkaAnalyticsPublisher publisher = new KafkaAnalyticsPublisher(kafka, metrics, breakers);
        open(Breakers.KAFKA);

        publisher.publish(new ClickEventCreatedEvent("abc", null, "hash", null, "agent", null));

        verifyNoInteractions(kafka);
        assertThat(meters.get("kafka.events.publish.skipped.count").counter().count()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAsyncSendsCountAgainstTheKafkaBreaker() {
        KafkaTemplate<String, ClickEventCreatedEvent> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        KafkaAnalyticsPublisher publisher = new KafkaAnalyticsPublisher(kafka, metrics, breakers);

        publisher.publish(new ClickEventCreatedEvent("abc", null, "hash", null, "agent", null));

        assertThat(breakers.circuitBreaker(Breakers.KAFKA).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // ============================================================
    // DYNAMODB
    // ============================================================

    @Test
    @SuppressWarnings("unchecked")
    void dynamoDbCallsFailFastWhileOpen() {
        DynamoDbTable<UrlMappingItem> table = mock(DynamoDbTable.class);
        DynamoDbClient client = mock(DynamoDbClient.class);
        UrlMappingDynamoRepository repository = new UrlMappingDynamoRepository(table, client, breakers);
        open(Breakers.DYNAMODB);

        assertThatThrownBy(() -> repository.findByShortUrl("abc")).isInstanceOf(CallNotPermittedException.class);
        verifyNoInteractions(table, client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTakenShortCodeIsNotAnOutage() {
        DynamoDbTable<UrlMappingItem> table = mock(DynamoDbTable.class);
        doThrow(ConditionalCheckFailedException.builder().statusCode(400).message("taken").build())
                .when(table).putItem(any(PutItemEnhancedRequest.class));
        UrlMappingDynamoRepository repository = new UrlMappingDynamoRepository(table, mock(DynamoDbClient.class), breakers);

        for (int i = 0; i < 20; i++) {
            assertThat(repository.saveIfNotExists(new UrlMappingItem())).isFalse();
        }

        assertThat(breakers.circuitBreaker(Breakers.DYNAMODB).getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breakers.circuitBreaker(Breakers.DYNAMODB).getState().name()).isEqualTo("CLOSED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedDynamoDbTimeoutsOpenTheBreaker() {
        DynamoDbTable<UrlMappingItem> table = mock(DynamoDbTable.class);
        when(table.getItem(any(Key.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("timeout"));
        UrlMappingDynamoRepository repository = new UrlMappingDynamoRepository(table, mock(DynamoDbClient.class), breakers);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> repository.findByShortUrl("abc"));
        }

        assertThat(breakers.circuitBreaker(Breakers.DYNAMODB).getState().name()).isEqualTo("OPEN");
    }

    // ============================================================
    // MONGODB
    // ============================================================

    @Test
    void analyticsAreUnavailableRatherThanZeroWhileMongoDbIsDown() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ClickEventMongoRepository repo = mock(ClickEventMongoRepository.class);
        when(repo.findByShortUrlAndClickDateBetween(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("timed out"));
        MongoAnalyticsStorage storage = new MongoAnalyticsStorage(repo, mongo, metrics, breakers);
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> storage.findByShortUrlAndDateBetween("abc", now.minusDays(1), now))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");

        open(Breakers.MONGODB);
        reset(repo);
        assertThatThrownBy(() -> storage.findAggregatedByShortUrlsAndDateBetween(List.of("abc"), now.minusDays(1), now))
                .isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(repo, mongo);
    }

    @Test
    void storingAClickWhileMongoDbIsOpenFailsSoTheConsumerWaits() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ClickEventMongoRepository repo = mock(ClickEventMongoRepository.class);
        MongoAnalyticsStorage storage = new MongoAnalyticsStorage(repo, mongo, metrics, breakers);
        open(Breakers.MONGODB);

        assertThatThrownBy(() -> storage.saveClickEvent("e1", "abc", LocalDateTime.now(), "h", null, "a"))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(Breakers.mongoDbUnavailable(
                catchThrowable(() -> storage.saveClickEvent("e2", "abc", LocalDateTime.now(), "h", null, "a")))).isTrue();
        verifyNoInteractions(repo, mongo);
    }

    @Test
    void deletingAnalyticsIsBestEffortWhileMongoDbIsOpen() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ClickEventMongoRepository repo = mock(ClickEventMongoRepository.class);
        MongoAnalyticsStorage storage = new MongoAnalyticsStorage(repo, mongo, metrics, breakers);
        open(Breakers.MONGODB);

        storage.deleteByShortUrl("abc");
        assertThat(storage.deleteClickDetailsBefore("abc", LocalDateTime.now())).isZero();
        verifyNoInteractions(repo, mongo);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
