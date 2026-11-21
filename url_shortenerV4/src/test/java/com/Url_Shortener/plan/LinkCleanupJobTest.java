package com.Url_Shortener.plan;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.repository.InMemoryUrlMappingRepository;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.storage.AnalyticsStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LinkCleanupJobTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private InMemoryUrlMappingRepository links;
    private AnalyticsStorage analytics;
    private CacheService cache;
    private UserRepository users;
    private LinkCleanupJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        links = new InMemoryUrlMappingRepository();
        analytics = mock(AnalyticsStorage.class);
        cache = mock(CacheService.class);
        users = mock(UserRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        PlanService planService = new PlanService(users, redis, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        job = new LinkCleanupJob(links, analytics, cache, planService);
    }

    private void owner(long id, Plan plan, LocalDateTime periodEnd) {
        User user = new User();
        user.setId(id);
        user.setPlan(plan);
        user.setPlanPeriodEnd(periodEnd);
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    private void link(String code, Long ownerId, LocalDateTime created, LocalDateTime expiresAt) {
        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(code);
        item.setId((long) code.hashCode());
        item.setOwnerId(ownerId);
        item.setCreatedDate(created.format(ISO));
        item.setExpiresAt(expiresAt != null ? expiresAt.format(ISO) : null);
        links.save(item);
    }

    @Test
    void deletesLinksThirtyDaysAfterTheirCountdownStarted() {
        owner(1L, Plan.FREE, null);
        link("old-free", 1L, NOW.minusDays(30), NOW.minusDays(25));
        link("hidden-free", 1L, NOW.minusDays(29), NOW.minusDays(24));
        link("old-guest", null, NOW.minusDays(31), NOW.minusDays(26));

        LinkCleanupJob.Result result = job.run();

        assertThat(result.linksDeleted()).isEqualTo(2);
        assertThat(links.findByShortUrl("old-free")).isEmpty();
        assertThat(links.findByShortUrl("old-guest")).isEmpty();
        assertThat(links.findByShortUrl("hidden-free")).isPresent();
        verify(analytics).deleteByShortUrl("old-free");
        verify(cache).evict("old-free");
    }

    @Test
    void deletesALapsedUsersPaidLinksThirtyDaysAfterTheLapse() {
        owner(2L, Plan.MONTHLY, NOW.minusDays(37)); // lapsed 30 days ago
        owner(3L, Plan.MONTHLY, NOW.minusDays(36)); // lapsed 29 days ago
        link("lapsed-30", 2L, NOW.minusYears(1), null);
        link("lapsed-29", 3L, NOW.minusYears(1), null);

        job.run();

        assertThat(links.findByShortUrl("lapsed-30")).isEmpty();
        assertThat(links.findByShortUrl("lapsed-29")).isPresent();
    }

    @Test
    void trimsIndividualClicksByTheOwnersPlan() {
        owner(1L, Plan.FREE, null);
        owner(2L, Plan.MONTHLY, NOW.plusDays(10));
        owner(3L, Plan.YEARLY, NOW.plusDays(10));
        link("free", 1L, NOW.minusDays(1), NOW.plusDays(4));
        link("monthly", 2L, NOW.minusDays(100), null);
        link("yearly", 3L, NOW.minusDays(100), null);
        link("guest", null, NOW.minusDays(1), NOW.plusDays(4));
        when(analytics.deleteClickDetailsBefore(any(), any())).thenReturn(1L);

        LinkCleanupJob.Result result = job.run();

        verify(analytics).deleteClickDetailsBefore("free", NOW.minusDays(30));
        verify(analytics).deleteClickDetailsBefore("guest", NOW.minusDays(30));
        verify(analytics).deleteClickDetailsBefore("monthly", NOW.minusDays(90));
        verify(analytics).deleteClickDetailsBefore("yearly", NOW.minusDays(3 * 365));
        assertThat(result.clicksTrimmed()).isEqualTo(4);
        assertThat(result.linksDeleted()).isZero();
    }

    @Test
    void expiredButKeptLinksAreTrimmedLikeFreeLinks() {
        owner(2L, Plan.MONTHLY, NOW.plusDays(10));
        // Expired before an upgrade; its data is still within the 30 days.
        link("expired", 2L, NOW.minusDays(9), NOW.minusDays(4));

        job.run();

        verify(analytics).deleteClickDetailsBefore("expired", NOW.minusDays(30));
    }

    @Test
    void oneFailingLinkDoesNotStopTheRun() {
        owner(1L, Plan.FREE, null);
        link("broken", 1L, NOW.minusDays(1), NOW.plusDays(4));
        link("fine", 1L, NOW.minusDays(1), NOW.plusDays(4));
        when(analytics.deleteClickDetailsBefore(eq("broken"), any())).thenThrow(new RuntimeException("mongo"));

        LinkCleanupJob.Result result = job.run();

        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.linksChecked()).isEqualTo(2);
        verify(analytics).deleteClickDetailsBefore(eq("fine"), any());
    }
}
