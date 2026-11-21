package com.Url_Shortener.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *   - If Redis is unavailable, operations silently fall back
 *     to DynamoDB — the application continues working.
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

    public RedisCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.cache.redis.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Override
    public Optional<CachedUrlData> get(String shortUrl) {
        try {
            String json = redisTemplate.opsForValue().get(shortUrl);
            if (json != null && !json.isEmpty()) {
                CachedUrlData data = objectMapper.readValue(json, CachedUrlData.class);
                log.debug("Redis HIT for shortUrl: {}", shortUrl);
                return Optional.of(data);
            }
            log.debug("Redis MISS for shortUrl: {}", shortUrl);
            return Optional.empty();
        } catch (Exception e) {
            // Redis failure → fall back to DynamoDB. Never throw from cache.
            log.warn("Redis GET failed for shortUrl '{}': {}. Falling back to DynamoDB.",
                    shortUrl, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String shortUrl, CachedUrlData data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(shortUrl, json, ttl);
            redisTemplate.delete(MISSING_KEY_PREFIX + shortUrl);
            log.debug("Redis PUT for shortUrl: {}", shortUrl);
        } catch (JsonProcessingException e) {
            log.warn("Redis PUT serialization failed for shortUrl '{}': {}. Data is still persisted in DynamoDB.",
                    shortUrl, e.getMessage());
        } catch (Exception e) {
            // Redis failure → data was already persisted in DynamoDB.
            // The cache will be populated on the next read (cache-aside).
            log.warn("Redis PUT failed for shortUrl '{}': {}. Data is still persisted in DynamoDB.",
                    shortUrl, e.getMessage());
        }
    }

    @Override
    public void evict(String shortUrl) {
        try {
            redisTemplate.delete(shortUrl);
            log.debug("Redis EVICT for shortUrl: {}", shortUrl);
        } catch (Exception e) {
            // Redis failure → the TTL will eventually expire the stale entry.
            log.warn("Redis EVICT failed for shortUrl '{}': {}. Stale cache entry will expire via TTL.",
                    shortUrl, e.getMessage());
        }
    }

    @Override
    public void markMissing(String shortUrl) {
        try {
            redisTemplate.opsForValue().set(MISSING_KEY_PREFIX + shortUrl, "1", MISSING_TTL);
        } catch (Exception e) {
            log.warn("Redis markMissing failed for shortUrl '{}': {}", shortUrl, e.getMessage());
        }
    }

    @Override
    public boolean isMarkedMissing(String shortUrl) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(MISSING_KEY_PREFIX + shortUrl));
        } catch (Exception e) {
            log.warn("Redis isMarkedMissing failed for shortUrl '{}': {}", shortUrl, e.getMessage());
            return false;
        }
    }
}
