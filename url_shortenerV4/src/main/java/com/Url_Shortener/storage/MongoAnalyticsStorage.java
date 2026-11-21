package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.ClickEventDocument;
import com.Url_Shortener.repository.ClickEventMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ============================================================
 * MONGODB MIGRATION: Mongo Analytics Storage
 * ============================================================
 *
 * Implements AnalyticsStorage using MongoDB. This is the ONLY class
 * in the application that knows about MongoDB-specific types.
 *
 * ERROR HANDLING:
 * Queries wrap operations in try/catch: on error they return
 * empty/zero results and log a warning. saveClickEvent rethrows
 * so the Kafka consumer can retry and dead-letter failed events.
 */
@Service
public class MongoAnalyticsStorage implements AnalyticsStorage {

    private static final Logger log = LoggerFactory.getLogger(MongoAnalyticsStorage.class);

    private final ClickEventMongoRepository mongoRepository;
    private final MongoTemplate mongoTemplate;
    private final UrlShortenerMetrics metrics;

    public MongoAnalyticsStorage(ClickEventMongoRepository mongoRepository,
                                  MongoTemplate mongoTemplate,
                                  UrlShortenerMetrics metrics) {
        this.mongoRepository = mongoRepository;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
    }

    // ============================================================
    // WRITE
    // ============================================================

    @Override
    public boolean saveClickEvent(String eventId, String shortUrl, LocalDateTime clickDate,
                                  String ipAddress, String referrer, String userAgent) {
        var sample = metrics.startTimer();
        try {
            mongoRepository.insert(new ClickEventDocument(
                    eventId, shortUrl, clickDate, ipAddress, referrer, userAgent));
            metrics.incrementMongoWrite();
            metrics.stopMongoPersistTime(sample);
            return true;
        } catch (DuplicateKeyException e) {
            metrics.stopMongoPersistTime(sample);
            log.debug("Ignoring redelivered click event: eventId={}", eventId);
            return false;
        } catch (Exception e) {
            metrics.incrementMongoWriteFailure();
            metrics.stopMongoPersistTime(sample);
            log.warn("Failed to log click event for shortUrl '{}': {}", shortUrl, e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // QUERY: Single URL — Detailed Click Events
    // ============================================================

    @Override
    public List<ClickEventDetailDto> findByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
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
        try {
            return mongoRepository.countByShortUrlAndClickDateBetween(shortUrl, start, end);
        } catch (Exception e) {
            log.warn("Failed to count clicks for shortUrl '{}': {}", shortUrl, e.getMessage());
            return 0;
        }
    }

    // ============================================================
    // AGGREGATION: Clicks Grouped by Date
    // ============================================================

    @Override
    public List<ClickEventDto> findAggregatedByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        return findAggregatedByShortUrlsAndDateBetween(List.of(shortUrl), start, end);
    }

    @Override
    public List<ClickEventDto> findAggregatedByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        if (shortUrls.isEmpty()) {
            return Collections.emptyList();
        }
        var sample = metrics.startTimer();
        try {
            // LocalDateTime is stored using the system zone, so group days in that zone too.
            TypedAggregation<ClickEventDocument> aggregation = Aggregation.newAggregation(
                    ClickEventDocument.class,
                    Aggregation.match(inRange(shortUrls, start, end)),
                    Aggregation.project()
                            .and(DateOperators.DateToString.dateOf("clickDate")
                                    .toString("%Y-%m-%d")
                                    .withTimezone(DateOperators.Timezone.valueOf(ZoneId.systemDefault().getId())))
                            .as("day"),
                    Aggregation.group("day").count().as("count"),
                    Aggregation.sort(Sort.Direction.ASC, "_id"));

            return mongoTemplate.aggregate(aggregation, GroupCount.class).getMappedResults().stream()
                    .map(group -> {
                        ClickEventDto dto = new ClickEventDto();
                        dto.setClickDate(LocalDate.parse(group.id));
                        dto.setCount(group.count);
                        return dto;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to aggregate clicks for {} shortUrls: {}", shortUrls.size(), e.getMessage());
            return Collections.emptyList();
        } finally {
            metrics.stopAnalyticsQueryTime(sample);
        }
    }

    // ============================================================
    // AGGREGATION: Clicks Grouped by Short URL
    // ============================================================

    @Override
    public Map<String, Long> countPerShortUrlAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        if (shortUrls.isEmpty()) {
            return Collections.emptyMap();
        }
        var sample = metrics.startTimer();
        try {
            TypedAggregation<ClickEventDocument> aggregation = Aggregation.newAggregation(
                    ClickEventDocument.class,
                    Aggregation.match(inRange(shortUrls, start, end)),
                    Aggregation.group("shortUrl").count().as("count"));

            return mongoTemplate.aggregate(aggregation, GroupCount.class).getMappedResults().stream()
                    .collect(Collectors.toMap(group -> group.id, group -> group.count));
        } catch (Exception e) {
            log.warn("Failed to count clicks per URL for {} shortUrls: {}", shortUrls.size(), e.getMessage());
            return Collections.emptyMap();
        } finally {
            metrics.stopAnalyticsQueryTime(sample);
        }
    }

    // ============================================================
    // COUNT: Multiple URLs in Date Range
    // ============================================================

    @Override
    public long countByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
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

    private Criteria inRange(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        return Criteria.where("shortUrl").in(shortUrls).and("clickDate").gte(start).lt(end);
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

    private static class GroupCount {
        String id;
        long count;
    }
}
