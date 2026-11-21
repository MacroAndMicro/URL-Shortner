package com.Url_Shortener.plan;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private UrlMappingDynamoRepository links;
    private CacheService cache;
    private StringRedisTemplate redis;
    private SubscriptionService subscriptions;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        links = mock(UrlMappingDynamoRepository.class);
        cache = mock(CacheService.class);
        redis = mock(StringRedisTemplate.class);
        PlanService planService = new PlanService(users, redis, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        subscriptions = new SubscriptionService(planService, users, links, cache);
        user = new User();
        user.setId(7L);
    }

    private UrlMappingItem link(String code, LocalDateTime created, LocalDateTime expiresAt) {
        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(code);
        item.setOwnerId(7L);
        item.setCreatedDate(created.format(ISO));
        item.setExpiresAt(expiresAt != null ? expiresAt.format(ISO) : null);
        return item;
    }

    private void owns(UrlMappingItem... items) {
        when(links.findByOwnerId(7L)).thenReturn(List.of(items));
    }

    @Test
    void upgradingMakesWorkingLinksPermanentButNotExpiredOnes() {
        owns(link("alive", NOW.minusDays(2), NOW.plusDays(3)),
             link("dead", NOW.minusDays(9), NOW.minusDays(4)));

        subscriptions.changeSubscription(user, Plan.MONTHLY, NOW.plusMonths(1));

        verify(links).setExpiresAt("alive", null);
        verify(links, never()).setExpiresAt(eq("dead"), any());
        verify(cache).evict("alive");
        verify(redis).delete("plan:7");
    }

    @Test
    void upgradingStoresTheExpiryOfLinksThatOnlyExpiredByComputation() {
        // Made before expiry was stored: expired by the created + 5 days rule.
        owns(link("legacy", NOW.minusDays(6), null));

        subscriptions.changeSubscription(user, Plan.YEARLY, NOW.plusYears(1));

        verify(links).setExpiresAt("legacy", NOW.minusDays(1).format(ISO));
    }

    @Test
    void movingToFreeStartsTheFiveDayCountdownForPaidLinks() {
        user.setPlan(Plan.YEARLY);
        user.setPlanPeriodEnd(NOW.plusDays(100));
        owns(link("paid", NOW.minusDays(200), null));

        subscriptions.changeSubscription(user, Plan.FREE, null);

        verify(links).setExpiresAt("paid", NOW.plusDays(5).format(ISO));
        assertThat(user.getPlanPeriodEnd()).isNull();
    }

    @Test
    void reSubscribingAfterALapseDoesNotRevivePaidLinksThatExpired() {
        // Grace ended 6 days ago, so links made while paid expired a day ago.
        user.setPlan(Plan.MONTHLY);
        user.setPlanPeriodEnd(NOW.minusDays(13));
        owns(link("old", NOW.minusDays(60), null));

        subscriptions.changeSubscription(user, Plan.MONTHLY, NOW.plusMonths(1));

        verify(links).setExpiresAt("old", NOW.minusDays(1).format(ISO));
    }

    @Test
    void payingWithinTheFiveDaysAfterALapseSavesTheLinks() {
        user.setPlan(Plan.MONTHLY);
        user.setPlanPeriodEnd(NOW.minusDays(9)); // lapsed 2 days ago
        owns(link("old", NOW.minusDays(60), null));

        subscriptions.changeSubscription(user, Plan.MONTHLY, NOW.plusMonths(1));

        verify(links, never()).setExpiresAt(any(), any());
    }

    @Test
    void backdatedLapseLeavesPaidLinksToTheComputedCountdown() {
        user.setPlan(Plan.YEARLY);
        user.setPlanPeriodEnd(NOW.plusDays(100));
        owns(link("paid", NOW.minusDays(200), null));

        // Payment 8 days late: lapsed a day ago.
        subscriptions.changeSubscription(user, Plan.YEARLY, NOW.minusDays(8));

        verify(links, never()).setExpiresAt(any(), any());
    }

    @Test
    void linksAlreadyCountingDownKeepTheirExpiry() {
        owns(link("free", NOW.minusDays(1), NOW.plusDays(4)));

        subscriptions.changeSubscription(user, Plan.FREE, null);

        verify(links, never()).setExpiresAt(any(), isNull());
        verify(links, never()).setExpiresAt(any(), any());
    }

    @Test
    void invalidSubscriptionsAreRejected() {
        assertThatThrownBy(() -> subscriptions.changeSubscription(user, Plan.GUEST, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscriptions.changeSubscription(user, Plan.MONTHLY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
