package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.ClickEventDocument;
import com.Url_Shortener.model.DailyClickTotalDocument;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.repository.ClickEventMongoRepository;
import com.Url_Shortener.resilience.Breakers;
import com.Url_Shortener.resilience.Guarded;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
 * MONGODB: Analytics Storage
 * ============================================================
 *
 * Implements AnalyticsStorage using MongoDB. This is the ONLY class
 * in the application that knows about MongoDB-specific types.
 *
 * Two collections:
 *   click_events        one document per click; trimmed per plan
 *   daily_click_totals  one counter per link per day; kept while the link exists
 * All per-day and per-link counts come from daily_click_totals, so they
 * survive the trimming of click_events.
 *
 * ERROR HANDLING:
 * Calls go through the "mongodb" circuit breaker, so during an outage
 * they fail at once instead of waiting for MongoDB timeouts.
 * - Queries throw ServiceUnavailableException (503) when MongoDB cannot
 *   answer, rather than returning zeros that would look like real data.
 * - saveClickEvent rethrows, so the Kafka consumer waits and retries.
 * - Deletes are best effort: on failure they log and carry on.
 */
@Service
public class MongoAnalyticsStorage implements AnalyticsStorage {

    private static final Logger log = LoggerFactory.getLogger(MongoAnalyticsStorage.class);

    /** Removed: it deleted clicks after 400 days for every plan. */
    static final String OLD_TTL_INDEX = "click_date_ttl";

    static final String UNAVAILABLE = "Analytics are temporarily unavailable. Please try again shortly.";

    private final ClickEventMongoRepository mongoRepository;
    private final MongoTemplate mongoTemplate;
    private final UrlShortenerMetrics metrics;
    private final Guarded mongo;

    @Autowired
    public MongoAnalyticsStorage(ClickEventMongoRepository mongoRepository,
                                  MongoTemplate mongoTemplate,
                                  UrlShortenerMetrics metrics,
                                  CircuitBreakerRegistry breakers) {
        this.mongoRepository = mongoRepository;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.mongo = new Guarded(breakers.circuitBreaker(Breakers.MONGODB), log);
    }

    /** With a breaker of its own; for tests. */
    public MongoAnalyticsStorage(ClickEventMongoRepository mongoRepository,
                                  MongoTemplate mongoTemplate,
                                  UrlShortenerMetrics metrics) {
        this(mongoRepository, mongoTemplate, metrics, CircuitBreakerRegistry.ofDefaults());
    }

    /** A query through the breaker; a 503 for the user if MongoDB cannot answer. */
    private <T> T query(String what, java.util.function.Supplier<T> call) {
        try {
            return mongo.call(call);
        } catch (RuntimeException e) {
            mongo.logFailure(what, e);
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
    }

    // ============================================================
    // STARTUP: upgrade existing data
    // ============================================================

    /**
     * Drops the old 400-day TTL index and, the first time, fills
     * daily_click_totals from existing click events. Runs in the background
     * and only logs on failure, so a MongoDB outage does not stop startup;
     * it is attempted again on the next start.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void upgradeExistingData() {
        Thread thread = new Thread(() -> {
            try {
                dropOldTtlIndex();
                backfillDailyTotals();
            } catch (Exception e) {
                log.warn("Analytics data upgrade skipped, will retry on next start: {}", e.getMessage());
            }
        }, "analytics-upgrade");
        thread.setDaemon(true);
        thread.start();
    }

    void dropOldTtlIndex() {
        var indexOps = mongoTemplate.indexOps(ClickEventDocument.class);
        if (indexOps.getIndexInfo().stream().anyMatch(i -> OLD_TTL_INDEX.equals(i.getName()))) {
            indexOps.dropIndex(OLD_TTL_INDEX);
            log.info("Dropped index '{}' on click_events; retention is now per plan.", OLD_TTL_INDEX);
        }
    }

    /**
     * Builds daily totals from click events when none exist yet. Clicks
     * stored while this runs may be counted twice; it runs once, at the
     * first start after the upgrade.
     */
    void backfillDailyTotals() {
        if (mongoTemplate.estimatedCount(DailyClickTotalDocument.class) > 0
                || mongoTemplate.estimatedCount(ClickEventDocument.class) == 0) {
            return;
        }
        TypedAggregation<ClickEventDocument> aggregation = Aggregation.newAggregation(
                ClickEventDocument.class,
                Aggregation.project("shortUrl").and(dayOf("clickDate")).as("day"),
                Aggregation.group("shortUrl", "day").count().as("count"));
        List<Document> groups = mongoTemplate.aggregate(aggregation, Document.class).getMappedResults();
        for (Document group : groups) {
            Document key = group.get("_id", Document.class);
            String shortUrl = key.getString("shortUrl");
            String day = key.getString("day");
            mongoTemplate.upsert(
                    Query.query(Criteria.where("_id").is(DailyClickTotalDocument.idOf(shortUrl, day))),
                    new Update().set("shortUrl", shortUrl).set("day", day).set("count", group.get("count", Number.class).longValue()),
                    DailyClickTotalDocument.class);
        }
        log.info("Built daily click totals for {} link-days from existing click events.", groups.size());
    }

    // ============================================================
    // WRITE
    // ============================================================

    /**
     * Stores the click and adds it to its daily total, in three steps: insert
     * the click marked uncounted, add it to the day's total, mark it counted.
     *
     * A retry of the same event (Kafka redelivery, or after a reply was lost
     * while MongoDB was down) finds the click already stored. If it is still
     * uncounted, the earlier attempt stopped before the total was updated, so
     * this attempt finishes it; if counted, it is a true duplicate and skipped.
     * Only an update whose reply is lost after it was applied can still be
     * counted twice.
     *
     * @return true if this call counted the click (the caller then updates
     *         the link's click count), false for a duplicate
     */
    @Override
    public boolean saveClickEvent(String eventId, String shortUrl, LocalDateTime clickDate,
                                  String ipAddress, String referrer, String userAgent) {
        var sample = metrics.startTimer();
        try {
            boolean counted = mongo.call(() -> {
                try {
                    mongoRepository.insert(new ClickEventDocument(
                            eventId, shortUrl, clickDate, ipAddress, referrer, userAgent));
                } catch (DuplicateKeyException e) {
                    ClickEventDocument existing = mongoTemplate.findOne(
                            Query.query(Criteria.where("eventId").is(eventId)), ClickEventDocument.class);
                    if (existing == null || !Boolean.FALSE.equals(existing.getCounted())) {
                        return false;
                    }
                }
                String day = clickDate.toLocalDate().toString();
                mongoTemplate.upsert(
                        Query.query(Criteria.where("_id").is(DailyClickTotalDocument.idOf(shortUrl, day))),
                        new Update().inc("count", 1).setOnInsert("shortUrl", shortUrl).setOnInsert("day", day),
                        DailyClickTotalDocument.class);
                mongoTemplate.updateFirst(Query.query(Criteria.where("eventId").is(eventId)),
                        new Update().set("counted", true), ClickEventDocument.class);
                return true;
            });
            metrics.stopMongoPersistTime(sample);
            if (counted) {
                metrics.incrementMongoWrite();
            } else {
                log.debug("Ignoring redelivered click event: eventId={}", eventId);
            }
            return counted;
        } catch (RuntimeException e) {
            metrics.incrementMongoWriteFailure();
            metrics.stopMongoPersistTime(sample);
            mongo.logFailure("Storing click event for shortUrl '" + shortUrl + "'", e);
            throw e;
        }
    }

    // ============================================================
    // QUERY: individual clicks
    // ============================================================

    @Override
    public List<ClickEventDetailDto> findByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        return query("Click details for '" + shortUrl + "'",
                () -> mongoRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end)).stream()
                .map(this::toDetailDto)
                .toList();
    }

    // ============================================================
    // QUERY: daily totals
    // Ranges are [start, end); totals are per whole day, so every day the
    // range touches is included.
    // ============================================================

    @Override
    public long countByShortUrlAndDateBetween(String shortUrl, LocalDateTime start, LocalDateTime end) {
        return countByShortUrlsAndDateBetween(List.of(shortUrl), start, end);
    }

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
            TypedAggregation<DailyClickTotalDocument> aggregation = Aggregation.newAggregation(
                    DailyClickTotalDocument.class,
                    Aggregation.match(daysInRange(shortUrls, start, end)),
                    Aggregation.group("day").sum("count").as("count"),
                    Aggregation.sort(Sort.Direction.ASC, "_id"));

            return query("Clicks per day for " + shortUrls.size() + " links",
                    () -> mongoTemplate.aggregate(aggregation, GroupCount.class).getMappedResults()).stream()
                    .map(group -> {
                        ClickEventDto dto = new ClickEventDto();
                        dto.setClickDate(LocalDate.parse(group.id));
                        dto.setCount(group.count);
                        return dto;
                    })
                    .toList();
        } finally {
            metrics.stopAnalyticsQueryTime(sample);
        }
    }

    @Override
    public Map<String, Long> countPerShortUrlAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        if (shortUrls.isEmpty()) {
            return Collections.emptyMap();
        }
        var sample = metrics.startTimer();
        try {
            TypedAggregation<DailyClickTotalDocument> aggregation = Aggregation.newAggregation(
                    DailyClickTotalDocument.class,
                    Aggregation.match(daysInRange(shortUrls, start, end)),
                    Aggregation.group("shortUrl").sum("count").as("count"));

            return query("Clicks per link for " + shortUrls.size() + " links",
                    () -> mongoTemplate.aggregate(aggregation, GroupCount.class).getMappedResults()).stream()
                    .collect(Collectors.toMap(group -> group.id, group -> group.count));
        } finally {
            metrics.stopAnalyticsQueryTime(sample);
        }
    }

    @Override
    public long countByShortUrlsAndDateBetween(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        return countPerShortUrlAndDateBetween(shortUrls, start, end).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    @Override
    public long countByShortUrl(String shortUrl) {
        return query("Total clicks for '" + shortUrl + "'",
                () -> mongoTemplate.find(Query.query(Criteria.where("shortUrl").is(shortUrl)),
                        DailyClickTotalDocument.class)).stream()
                .mapToLong(DailyClickTotalDocument::getCount).sum();
    }

    // ============================================================
    // DELETE (best effort)
    // ============================================================

    @Override
    public void deleteByShortUrl(String shortUrl) {
        mongo.runOr("Deleting click data for '" + shortUrl + "'", () -> {
            mongoRepository.deleteByShortUrl(shortUrl);
            mongoTemplate.remove(Query.query(Criteria.where("shortUrl").is(shortUrl)), DailyClickTotalDocument.class);
        });
    }

    @Override
    public long deleteClickDetailsBefore(String shortUrl, LocalDateTime cutoff) {
        return mongo.callOr("Trimming click events for '" + shortUrl + "'",
                () -> mongoTemplate.remove(Query.query(
                                Criteria.where("shortUrl").is(shortUrl).and("clickDate").lt(cutoff)),
                        ClickEventDocument.class).getDeletedCount(), 0L);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static Criteria daysInRange(List<String> shortUrls, LocalDateTime start, LocalDateTime end) {
        String firstDay = start.toLocalDate().toString();
        String lastDay = end.minusNanos(1).toLocalDate().toString();
        return Criteria.where("shortUrl").in(shortUrls).and("day").gte(firstDay).lte(lastDay);
    }

    /** yyyy-MM-dd of a stored LocalDateTime, in the zone it was stored in. */
    private static DateOperators.DateToString dayOf(String field) {
        return DateOperators.DateToString.dateOf(field)
                .toString("%Y-%m-%d")
                .withTimezone(DateOperators.Timezone.valueOf(ZoneId.systemDefault().getId()));
    }

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
