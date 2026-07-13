package com.Url_Shortener.cache;

import java.util.Optional;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: Cache Service Interface
 * ============================================================
 *
 * Abstraction for the caching layer. Business services depend
 * on this interface, NOT on RedisTemplate or any Redis-specific
 * implementation. This keeps the business logic clean and makes
 * the cache implementation swappable.
 *
 * All operations MUST be non-blocking for the caller — failures
 * are handled internally with graceful fallback to DynamoDB.
 */
public interface CacheService {

    /**
     * Retrieves cached URL data by short URL.
     *
     * @param shortUrl the short URL key
     * @return the cached data if present, empty Optional if not found or on error
     */
    Optional<CachedUrlData> get(String shortUrl);

    /**
     * Stores URL data in the cache with the configured TTL.
     *
     * @param shortUrl the short URL key
     * @param data     the URL data to cache
     */
    void put(String shortUrl, CachedUrlData data);

    /**
     * Removes a cached entry, e.g. after a delete or update.
     *
     * @param shortUrl the short URL key to evict
     */
    void evict(String shortUrl);
}
