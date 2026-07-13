package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================
 * MONGODB MIGRATION: Analytics Storage Interface
 * ============================================================
 *
 * Abstraction for analytics persistence. Business services depend
 * on this interface, NOT on MongoDB-specific repositories.
 *
 * Following the same pattern as CacheService — the interface is
 * implemented by MongoAnalyticsStorage, and in the future, a
 * Kafka-backed implementation can be added without changing
 * business logic.
 *
 * FUTURE KAFKA COMPATIBILITY:
 * The saveClickEvent method takes primitive parameters rather than
 * a domain object. When Kafka is introduced:
 *   - The Kafka producer wraps saveClickEvent
 *   - The Kafka consumer calls saveClickEvent independently
 *   - No changes needed in UrlMappingService or RedirectController
 *
 * All methods accept and return DTOs or standard Java types.
 * No MongoDB-specific types (Document, ClickEventDocument) leak
 * through this interface.
 */
public interface AnalyticsStorage {

    /**
     * Saves a click event for a redirect.
     * Called on every redirect (hot path).
     */
    void saveClickEvent(String shortUrl, String ipAddress, String referrer, String userAgent);

    /**
     * Returns detailed click events for a specific URL within a date range.
     */
    List<ClickEventDetailDto> findByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Counts click events for a specific URL within a date range.
     */
    long countByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Returns click event counts grouped by date for a specific URL.
     */
    List<ClickEventDto> findAggregatedByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Returns click event counts grouped by date for multiple URLs.
     */
    List<ClickEventDto> findAggregatedByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end);

    /**
     * Counts total click events for multiple URLs within a date range.
     */
    long countByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end);

    /**
     * Deletes all click events for a specific URL.
     */
    void deleteByShortUrl(String shortUrl);

    /**
     * Counts total click events for a specific URL (all time).
     */
    long countByShortUrl(String shortUrl);
}
