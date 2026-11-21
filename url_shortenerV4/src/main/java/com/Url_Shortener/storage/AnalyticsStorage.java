package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * MONGODB MIGRATION: Analytics Storage Interface
 * ============================================================
 *
 * Abstraction for analytics persistence. Business services depend
 * on this interface, NOT on MongoDB-specific repositories.
 *
 * All methods accept and return DTOs or standard Java types.
 * No MongoDB-specific types (Document, ClickEventDocument) leak
 * through this interface.
 */
public interface AnalyticsStorage {

    /**
     * Saves a click event. Called by the Kafka consumer.
     *
     * @return true if stored, false if this eventId was already stored
     * @throws RuntimeException if the write fails, so the event can be retried
     */
    boolean saveClickEvent(String eventId, String shortUrl, LocalDateTime clickDate,
                           String ipAddress, String referrer, String userAgent);

    /**
     * Returns detailed click events for a specific URL within a date range.
     */
    List<ClickEventDetailDto> findByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Counts clicks for a specific URL on the days the range touches.
     */
    long countByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Returns click counts per day for a specific URL.
     */
    List<ClickEventDto> findAggregatedByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end);

    /**
     * Returns click counts per day, summed over multiple URLs.
     */
    List<ClickEventDto> findAggregatedByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end);

    /**
     * Returns click counts per short URL within a date range.
     * URLs with no clicks are absent from the result.
     */
    Map<String, Long> countPerShortUrlAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end);

    /**
     * Counts total click events for multiple URLs within a date range.
     */
    long countByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end);

    /**
     * Deletes all click data for a specific URL: events and daily totals.
     */
    void deleteByShortUrl(String shortUrl);

    /**
     * Deletes a URL's individual click events from before cutoff, keeping its
     * daily totals.
     *
     * @return how many events were deleted
     */
    long deleteClickDetailsBefore(String shortUrl, LocalDateTime cutoff);

    /**
     * Counts total click events for a specific URL (all time).
     */
    long countByShortUrl(String shortUrl);
}
