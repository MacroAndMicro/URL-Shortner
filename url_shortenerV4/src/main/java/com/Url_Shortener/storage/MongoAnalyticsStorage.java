package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.ClickEventDocument;
import com.Url_Shortener.repository.ClickEventMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * MONGODB MIGRATION: Mongo Analytics Storage
 * ============================================================
 *
 * Implements AnalyticsStorage using MongoDB via
 * ClickEventMongoRepository. This is the ONLY class in the
 * application that knows about MongoDB-specific types.
 *
 * ERROR HANDLING:
 * Following the RedisCacheService pattern, all methods wrap
 * operations in try/catch. MongoDB failures must never prevent
 * redirects from working. On error, queries return empty/zero
 * results and log a warning.
 */
@Service
public class MongoAnalyticsStorage implements AnalyticsStorage {

    private static final Logger log = LoggerFactory.getLogger(MongoAnalyticsStorage.class);

    private final ClickEventMongoRepository mongoRepository;
    private final UrlShortenerMetrics metrics;

    public MongoAnalyticsStorage(ClickEventMongoRepository mongoRepository,
                                  UrlShortenerMetrics metrics) {
        this.mongoRepository = mongoRepository;
        this.metrics = metrics;
    }

    // ============================================================
    // WRITE
    // ============================================================

    @Override
    public void saveClickEvent(String shortUrl, String ipAddress, String referrer, String userAgent) {
        // OLD POSTGRES IMPLEMENTATION
        // ClickEvent clickEvent = new ClickEvent();
        // clickEvent.setClickDate(LocalDateTime.now());
        // clickEvent.setShortUrl(shortUrl);
        // clickEvent.setIpAddress(ipAddress);
        // clickEvent.setReferrer(referrer);
        // clickEvent.setUserAgent(userAgent);
        // clickEventRepository.save(clickEvent);

        // NEW MONGODB IMPLEMENTATION
        var sample = metrics.startTimer();
        try {
            ClickEventDocument doc = new ClickEventDocument(
                    shortUrl,
                    LocalDateTime.now(),
                    ipAddress,
                    referrer,
                    userAgent
            );
            mongoRepository.save(doc);
            metrics.incrementMongoWrite();
            metrics.stopMongoPersistTime(sample);
        } catch (Exception e) {
            // HOT PATH: Never throw to the caller. A failed analytics write
            // must not prevent the user from being redirected.
            metrics.incrementMongoWriteFailure();
            metrics.stopMongoPersistTime(sample);
            log.warn("Failed to log click event for shortUrl '{}': {}", shortUrl, e.getMessage());
        }
    }

    // ============================================================
    // QUERY: Single URL — Detailed Click Events
    // ============================================================

    @Override
    public List<ClickEventDetailDto> findByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        // OLD POSTGRES IMPLEMENTATION
        // return clickEventRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
        //         .map(click -> new ClickEventDetailDto(click.getId(), ...))
        //         .toList();

        // NEW MONGODB IMPLEMENTATION
        try {
            return mongoRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
                    .map(this::toDetailDto)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to query click event details for shortUrl '{}': {}", shortUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ============================================================
    // COUNT: Single URL in Date Range
    // ============================================================

    @Override
    public long countByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        // OLD POSTGRES IMPLEMENTATION
        // return clickEventRepository.countByShortUrlAndClickDateBetween(shortUrl, start, end);

        // NEW MONGODB IMPLEMENTATION
        try {
            return mongoRepository.countByShortUrlAndClickDateBetween(shortUrl, start, end);
        } catch (Exception e) {
            log.warn("Failed to count clicks for shortUrl '{}': {}", shortUrl, e.getMessage());
            return 0;
        }
    }

    // ============================================================
    // AGGREGATION: Single URL — Click Events Grouped by Date
    // ============================================================

    @Override
    public List<ClickEventDto> findAggregatedByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        // OLD POSTGRES IMPLEMENTATION
        // return clickEventRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
        //         .collect(Collectors.groupingBy(click -> click.getClickDate().toLocalDate(), Collectors.counting()))
        //         .entrySet().stream().map(entry -> { ClickEventDto dto = new ClickEventDto(); ... }).toList();

        // NEW MONGODB IMPLEMENTATION
        try {
            return mongoRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
                    .collect(Collectors.groupingBy(
                            doc -> doc.getClickDate().toLocalDate(),
                            Collectors.counting()))
                    .entrySet().stream()
                    .map(entry -> {
                        ClickEventDto dto = new ClickEventDto();
                        dto.setClickDate(entry.getKey());
                        dto.setCount(entry.getValue());
                        return dto;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to aggregate click events for shortUrl '{}': {}", shortUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ============================================================
    // AGGREGATION: Multiple URLs — Clicks Grouped by Date
    // ============================================================

    @Override
    public List<ClickEventDto> findAggregatedByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        // OLD POSTGRES IMPLEMENTATION
        // List<ClickEvent> clickEvents = clickEventRepository.findByShortUrlInAndClickDateBetween(shortUrls, ...);
        // return clickEvents.stream().collect(Collectors.groupingBy(...))...sorted()...

        // NEW MONGODB IMPLEMENTATION
        if (shortUrls.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return mongoRepository.findByShortUrlInAndClickDateBetween(shortUrls, start, end).stream()
                    .collect(Collectors.groupingBy(
                            doc -> doc.getClickDate().toLocalDate(),
                            Collectors.counting()))
                    .entrySet().stream()
                    .map(entry -> {
                        ClickEventDto dto = new ClickEventDto();
                        dto.setClickDate(entry.getKey());
                        dto.setCount(entry.getValue());
                        return dto;
                    })
                    .sorted((a, b) -> a.getClickDate().compareTo(b.getClickDate()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to aggregate clicks for {} shortUrls: {}", shortUrls.size(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ============================================================
    // COUNT: Multiple URLs in Date Range
    // ============================================================

    @Override
    public long countByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        // OLD POSTGRES IMPLEMENTATION
        // return shortUrls.isEmpty() ? 0 : clickEventRepository.countByShortUrlInAndClickDateBetween(shortUrls, start, end);

        // NEW MONGODB IMPLEMENTATION
        if (shortUrls.isEmpty()) {
            return 0;
        }
        try {
            return mongoRepository.countByShortUrlInAndClickDateBetween(shortUrls, start, end);
        } catch (Exception e) {
            log.warn("Failed to count clicks for {} shortUrls: {}", shortUrls.size(), e.getMessage());
            return 0;
        }
    }

    // ============================================================
    // DELETE
    // ============================================================

    @Override
    public void deleteByShortUrl(String shortUrl) {
        // OLD POSTGRES IMPLEMENTATION
        // clickEventRepository.deleteByShortUrl(shortUrl);

        // NEW MONGODB IMPLEMENTATION
        try {
            mongoRepository.deleteByShortUrl(shortUrl);
        } catch (Exception e) {
            log.warn("Failed to delete click events for shortUrl '{}': {}", shortUrl, e.getMessage());
        }
    }

    @Override
    public long countByShortUrl(String shortUrl) {
        try {
            return mongoRepository.countByShortUrl(shortUrl);
        } catch (Exception e) {
            log.warn("Failed to count clicks for shortUrl '{}': {}", shortUrl, e.getMessage());
            return 0;
        }
    }

    // ============================================================
    // Mapper: ClickEventDocument → ClickEventDetailDto
    // ============================================================

    private ClickEventDetailDto toDetailDto(ClickEventDocument doc) {
        return new ClickEventDetailDto(
                null,  // ID is auto-generated by MongoDB as String; not exposed as Long
                doc.getClickDate(),
                doc.getIpAddress(),
                doc.getReferrer(),
                doc.getUserAgent()
        );
    }
}
