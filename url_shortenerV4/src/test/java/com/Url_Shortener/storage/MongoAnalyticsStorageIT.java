package com.Url_Shortener.storage;

import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.ClickEventDocument;
import com.Url_Shortener.model.DailyClickTotalDocument;
import com.Url_Shortener.repository.ClickEventMongoRepository;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Daily totals, the upgrade of existing data, and click trimming, against a
 * real MongoDB.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoAnalyticsStorageIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    private static com.mongodb.client.MongoClient client;
    private MongoTemplate mongo;
    private MongoAnalyticsStorage storage;

    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 11, 20, 10, 0);
    private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 11, 21, 10, 0);

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getConnectionString());
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        mongo = new MongoTemplate(client, "test_" + UUID.randomUUID().toString().substring(0, 8));
        // The indexes the application creates at startup (auto-index-creation).
        var resolver = new MongoPersistentEntityIndexResolver(mongo.getConverter().getMappingContext());
        for (Class<?> type : List.of(ClickEventDocument.class, DailyClickTotalDocument.class)) {
            resolver.resolveIndexFor(type).forEach(mongo.indexOps(type)::ensureIndex);
        }
        ClickEventMongoRepository repository =
                new MongoRepositoryFactory(mongo).getRepository(ClickEventMongoRepository.class);
        storage = new MongoAnalyticsStorage(repository, mongo, new UrlShortenerMetrics(new SimpleMeterRegistry()));
    }

    private void click(String shortUrl, LocalDateTime at) {
        storage.saveClickEvent(UUID.randomUUID().toString(), shortUrl, at, "hash", null, "agent");
    }

    @Test
    void clicksAreCountedPerDay() {
        click("aaaa", DAY1);
        click("aaaa", DAY2);
        click("aaaa", DAY2.plusHours(1));
        click("bbbb", DAY2);

        List<ClickEventDto> perDay = storage.findAggregatedByShortUrlAndDateBetween(
                "aaaa", DAY1.toLocalDate().atStartOfDay(), DAY2.toLocalDate().atTime(23, 59, 59));

        assertThat(perDay).extracting(ClickEventDto::getClickDate)
                .containsExactly(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));
        assertThat(perDay).extracting(ClickEventDto::getCount).containsExactly(1L, 2L);
        assertThat(storage.countPerShortUrlAndDateBetween(List.of("aaaa", "bbbb"),
                DAY2.toLocalDate().atStartOfDay(), DAY2.toLocalDate().plusDays(1).atStartOfDay()))
                .containsEntry("aaaa", 2L).containsEntry("bbbb", 1L);
    }

    @Test
    void redeliveredClickIsNotCountedTwice() {
        String eventId = UUID.randomUUID().toString();
        assertThat(storage.saveClickEvent(eventId, "aaaa", DAY1, "hash", null, "agent")).isTrue();
        assertThat(storage.saveClickEvent(eventId, "aaaa", DAY1, "hash", null, "agent")).isFalse();

        assertThat(storage.countByShortUrl("aaaa")).isEqualTo(1);
    }

    @Test
    void trimmingDetailsKeepsDailyTotals() {
        click("aaaa", DAY1);
        click("aaaa", DAY2);

        assertThat(storage.deleteClickDetailsBefore("aaaa", DAY2.toLocalDate().atStartOfDay())).isEqualTo(1);

        assertThat(storage.findByShortUrlAndDateBetween("aaaa", DAY1.minusDays(1), DAY2.plusDays(1))).hasSize(1);
        assertThat(storage.countByShortUrl("aaaa")).isEqualTo(2);
    }

    @Test
    void deletingALinkRemovesItsClicksAndTotals() {
        click("aaaa", DAY1);
        click("bbbb", DAY1);

        storage.deleteByShortUrl("aaaa");

        assertThat(storage.countByShortUrl("aaaa")).isZero();
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), ClickEventDocument.class))
                .isEqualTo(1);
        assertThat(storage.countByShortUrl("bbbb")).isEqualTo(1);
    }

    @Test
    void upgradeBuildsTotalsFromExistingClicksAndDropsTheOldTtlIndex() {
        // State before this version: clicks without totals, and the 400-day TTL index.
        mongo.insert(new ClickEventDocument(UUID.randomUUID().toString(), "aaaa", DAY1, "h", null, "a"));
        mongo.insert(new ClickEventDocument(UUID.randomUUID().toString(), "aaaa", DAY1.plusHours(2), "h", null, "a"));
        mongo.insert(new ClickEventDocument(UUID.randomUUID().toString(), "aaaa", DAY2, "h", null, "a"));
        mongo.indexOps(ClickEventDocument.class).ensureIndex(new Index("clickDate", Sort.Direction.ASC)
                .named(MongoAnalyticsStorage.OLD_TTL_INDEX).expire(Duration.ofDays(400)));

        storage.dropOldTtlIndex();
        storage.backfillDailyTotals();
        storage.backfillDailyTotals(); // a second start does nothing

        assertThat(mongo.indexOps(ClickEventDocument.class).getIndexInfo())
                .noneMatch(i -> MongoAnalyticsStorage.OLD_TTL_INDEX.equals(i.getName()));
        assertThat(mongo.findAll(Document.class, "daily_click_totals"))
                .extracting(d -> d.getString("_id") + "=" + d.get("count"))
                .containsExactlyInAnyOrder(
                        DailyClickTotalDocument.idOf("aaaa", "2026-11-20") + "=2",
                        DailyClickTotalDocument.idOf("aaaa", "2026-11-21") + "=1");
    }
}
