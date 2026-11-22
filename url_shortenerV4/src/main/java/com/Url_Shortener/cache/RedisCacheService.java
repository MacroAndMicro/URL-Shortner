package com.Url_Shortener.cache;

import com.Url_Shortener.resilience.Breakers;
import com.Url_Shortener.resilience.Guarded;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: Redis Cache Service Implementation
 * ============================================================
 *
 * Implements CacheService using Redis as the backing store.
 * Uses StringRedisTemplate with explicit Jackson JSON serialization
 * to avoid classloader issues with type-specific Redis serializers.
 *
 * KEY DESIGN DECISIONS:
 *   - Redis is a CACHE, not a primary datastore.
 *   - DynamoDB remains the source of truth.
 *   - If Redis is unavailable, operations fall back to DynamoDB and the
 *     application continues working. Calls go through the "redis" circuit
 *     breaker, so during an outage they fail at once instead of each
 *     waiting for the Redis timeout.
 *   - TTL is configurable via app.cache.redis.ttl-hours.
 *
 * SERIALIZATION:
 *   Manual Jackson ObjectMapper serialization to JSON strings.
 *   Values stored as plain JSON in Redis String type.
 */
@Service
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String MISSING_KEY_PREFIX = "missing:";
    private static final Duration MISSING_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Guarded redis;

    public RedisCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry breakers,
            @Value("${app.cache.redis.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
        this.redis = new Guarded(breakers.circuitBreaker(Breakers.REDIS), log);
    }

    /** A miss (empty) when Redis is unavailable, so the caller reads DynamoDB. */
    @Override
    public Optional<CachedUrlData> get(String shortUrl) {
        String json = redis.callOr("Redis GET " + shortUrl, () -> redisTemplate.opsForValue().get(shortUrl), null);
        if (json == null || json.isEmpty()) {
            log.debug("Redis MISS for shortUrl: {}", shortUrl);
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, CachedUrlData.class));
        } catch (JsonProcessingException e) {
            log.warn("Unreadable cache entry for shortUrl '{}': {}", shortUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /** Best effort: if Redis is unavailable the cache is filled on a later read. */
    @Override
    public void put(String shortUrl, CachedUrlData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize cache entry for shortUrl '{}': {}", shortUrl, e.getMessage());
            return;
        }
        redis.runOr("Redis PUT " + shortUrl, () -> {
            redisTemplate.opsForValue().set(shortUrl, json, ttl);
            redisTemplate.delete(MISSING_KEY_PREFIX + shortUrl);
        });
    }

    /** Best effort: a stale entry otherwise expires with its TTL. */
    @Override
    public void evict(String shortUrl) {
        redis.runOr("Redis EVICT " + shortUrl, () -> redisTemplate.delete(shortUrl));
    }

    @Override
    public void markMissing(String shortUrl) {
        redis.runOr("Redis markMissing " + shortUrl,
                () -> redisTemplate.opsForValue().set(MISSING_KEY_PREFIX + shortUrl, "1", MISSING_TTL));
    }

    /** False when Redis is unavailable, so the caller checks DynamoDB. */
    @Override
    public boolean isMarkedMissing(String shortUrl) {
        return Boolean.TRUE.equals(redis.callOr("Redis isMarkedMissing " + shortUrl,
                () -> redisTemplate.hasKey(MISSING_KEY_PREFIX + shortUrl), false));
    }
}
