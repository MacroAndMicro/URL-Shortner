package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.UrlMappingNotFoundException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.plan.GuestQuotaService;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.publisher.AnalyticsPublisher;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.shortcode.Base62;
import com.Url_Shortener.shortcode.SnowflakeIdGenerator;
import com.Url_Shortener.storage.AnalyticsStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class UrlMappingServiceTest {

    private static final String CODE = "QN7XOa0a";
    private static final String TARGET = "https://example.com";
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private UrlMappingDynamoRepository repository;
    private AnalyticsStorage analyticsStorage;
    private AnalyticsPublisher publisher;
    private CacheService cache;
    private UserRepository userRepository;
    private GuestQuotaService guestQuota;
    private UrlMappingService service;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(UrlMappingDynamoRepository.class);
        analyticsStorage = mock(AnalyticsStorage.class);
        publisher = mock(AnalyticsPublisher.class);
        cache = mock(CacheService.class);
        userRepository = mock(UserRepository.class);
        guestQuota = mock(GuestQuotaService.class);

        // Redis plan cache always misses, so the owner is read from userRepository.
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));

        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        PlanService planService = new PlanService(userRepository, redis, clock);

        service = new UrlMappingService(repository, analyticsStorage, publisher, cache,
                new UrlShortenerMetrics(new SimpleMeterRegistry()), new IpHasher("salt"),
                new SnowflakeIdGenerator(0), planService, guestQuota);

        user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.findByOwnerAndOriginalUrl(any(), any())).thenReturn(List.of());
    }

    private void makePaid() {
        user.setPlan(Plan.MONTHLY);
        user.setPlanPeriodEnd(NOW.plusDays(10));
    }

    private UrlMappingItem item(String shortUrl, int clicks) {
        return item(shortUrl, clicks, NOW.minusDays(1));
    }

    private UrlMappingItem item(String shortUrl, int clicks, LocalDateTime created) {
        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(shortUrl);
        item.setId(42L);
        item.setOriginalUrl(TARGET);
        item.setOwnerId(1L);
        item.setClickCount(clicks);
        item.setCreatedDate(created.format(ISO));
        return item;
    }

    private void cached(UrlMappingItem item) {
        when(cache.get(item.getShortUrl())).thenReturn(Optional.of(CachedUrlData.from(item)));
    }

    // ============================================================
    // REDIRECT
    // ============================================================

    @Test
    void paidLinkRedirectsWith302AndRecordsTheClick() {
        makePaid();
        cached(item(CODE, 0));

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();

        assertThat(target.originalUrl()).isEqualTo(TARGET);
        assertThat(target.permanent()).isFalse();
        assertThat(target.maxAgeSeconds()).isNull();
        verifyNoInteractions(repository);
        verify(publisher).publish(any());
    }

    @Test
    void freeLinkRedirectsWith301CachedUntilItExpiresAndRecordsNoClick() {
        cached(item(CODE, 0, NOW.minusDays(1)));

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();

        assertThat(target.permanent()).isTrue();
        assertThat(target.maxAgeSeconds()).isEqualTo(4 * 24 * 3600L);
        verify(publisher, never()).publish(any());
    }

    @Test
    void guestLinkRedirectsWith301WithoutLookingUpAnOwner() {
        UrlMappingItem guestItem = item(CODE, 0, NOW.minusHours(1));
        guestItem.setOwnerId(null);
        cached(guestItem);

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();

        assertThat(target.permanent()).isTrue();
        verifyNoInteractions(userRepository);
        verify(publisher, never()).publish(any());
    }

    @Test
    void expiredFreeLinkIsNotFoundButNotMarkedMissing() {
        cached(item(CODE, 0, NOW.minusDays(5)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
        verify(cache, never()).markMissing(any());
    }

    @Test
    void lapsedSubscriptionAppliesFreeRulesToOldLinks() {
        user.setPlan(Plan.YEARLY);
        user.setPlanPeriodEnd(NOW.minusDays(8)); // grace ended a day ago
        cached(item(CODE, 0, NOW.minusDays(30)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
    }

    @Test
    void subscriptionInGracePeriodKeepsPaidRules() {
        user.setPlan(Plan.MONTHLY);
        user.setPlanPeriodEnd(NOW.minusDays(6));
        cached(item(CODE, 0, NOW.minusDays(30)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent"))
                .hasValueSatisfying(t -> assertThat(t.permanent()).isFalse());
    }

    @Test
    void cacheMissReadsDynamoDbAndFillsCacheWithoutIncrementing() {
        when(cache.get(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(CODE)).thenReturn(Optional.of(item(CODE, 0)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isPresent();

        verify(cache).put(eq(CODE), any());
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void unknownCodeIsMarkedMissingAndThenSkipsDynamoDb() {
        when(cache.get(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(CODE)).thenReturn(Optional.empty());

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
        verify(cache).markMissing(CODE);
        verify(publisher, never()).publish(any());

        reset(repository);
        when(cache.isMarkedMissing(CODE)).thenReturn(true);

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void publishedEventContainsHashedIpNotTheRawAddress() {
        makePaid();
        cached(item(CODE, 0));

        service.resolve(CODE, "1.2.3.4", null, "agent");

        ArgumentCaptor<ClickEventCreatedEvent> event = ArgumentCaptor.forClass(ClickEventCreatedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().getIpAddress()).isNotEqualTo("1.2.3.4").hasSize(64);
    }

    // ============================================================
    // CREATE (signed in)
    // ============================================================

    @Test
    void createUsesTheBase62SnowflakeIdAsTheShortCode() {
        when(repository.saveIfNotExists(any())).thenReturn(true);

        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        assertThat(dto.getShortUrl()).matches("[0-9A-Za-z]{10,11}");
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void freeLinkExpiresAfterFiveDaysAndPaidLinkNever() {
        when(repository.saveIfNotExists(any())).thenReturn(true);

        assertThat(service.createShortUrl(TARGET, user).getExpiresAt()).isEqualTo(NOW.plusDays(5));

        makePaid();
        assertThat(service.createShortUrl("https://example.com/other", user).getExpiresAt()).isNull();
    }

    @Test
    void createStoresTheDuplicateLookupKeyWithoutLoadingTheUsersUrls() {
        when(repository.saveIfNotExists(any())).thenReturn(true);

        service.createShortUrl(TARGET, user);

        ArgumentCaptor<UrlMappingItem> saved = ArgumentCaptor.forClass(UrlMappingItem.class);
        verify(repository).saveIfNotExists(saved.capture());
        assertThat(saved.getValue().getOwnerUrlKey())
                .isEqualTo(UrlMappingItem.ownerUrlKey(1L, TARGET))
                .startsWith("1#")
                .hasSize(2 + 64);
        verify(repository, never()).findByOwnerId(any());
    }

    @Test
    void createRetriesWithANewCodeOnCollision() {
        when(repository.saveIfNotExists(any())).thenReturn(false, true);

        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        verify(repository, times(2)).saveIfNotExists(any());
        assertThat(dto.getShortUrl()).matches("[0-9A-Za-z]{10,11}");
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void createFailsAfterRepeatedCollisions() {
        when(repository.saveIfNotExists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createReturnsExistingActiveMappingForSameUrl() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(List.of(item(CODE, 3)));

        assertThat(service.createShortUrl(TARGET, user).getShortUrl()).isEqualTo(CODE);
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void createIgnoresAnExpiredDuplicate() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET))
                .thenReturn(List.of(item(CODE, 3, NOW.minusDays(6))));
        when(repository.saveIfNotExists(any())).thenReturn(true);

        assertThat(service.createShortUrl(TARGET, user).getShortUrl()).isNotEqualTo(CODE);
    }

    @Test
    void freeUserAtTheLimitCannotCreateMoreAndOnlyActiveLinksCount() {
        String fiveDaysAgo = NOW.minusDays(5).format(ISO);
        when(repository.countByOwnerId(1L, fiveDaysAgo)).thenReturn(50L);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).contains("50").contains("Free");
                });
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void paidUserLimitCountsAllLinks() {
        makePaid();
        when(repository.countByOwnerId(eq(1L), isNull())).thenReturn(150L);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("150");
    }

    // ============================================================
    // CREATE (guest)
    // ============================================================

    @Test
    void guestLinkHasNoOwnerAndExpiresAfterFiveDays() {
        when(repository.saveIfNotExists(any())).thenReturn(true);

        UrlMappingDto dto = service.createGuestShortUrl(TARGET, "guest-id", "1.2.3.4");

        verify(guestQuota).acquire("guest-id", "1.2.3.4");
        ArgumentCaptor<UrlMappingItem> saved = ArgumentCaptor.forClass(UrlMappingItem.class);
        verify(repository).saveIfNotExists(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isNull();
        assertThat(saved.getValue().getOwnerUrlKey()).isNull();
        assertThat(dto.getExpiresAt()).isEqualTo(NOW.plusDays(5));
    }

    @Test
    void guestSlotIsReleasedWhenCreationFails() {
        when(repository.saveIfNotExists(any())).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> service.createGuestShortUrl(TARGET, "guest-id", "1.2.3.4"))
                .isInstanceOf(RuntimeException.class);
        verify(guestQuota).release("guest-id");
    }

    @Test
    void guestOverLimitCreatesNothing() {
        doThrow(LimitExceededException.forbidden("limit")).when(guestQuota).acquire(any(), any());

        assertThatThrownBy(() -> service.createGuestShortUrl(TARGET, "guest-id", "1.2.3.4"))
                .isInstanceOf(LimitExceededException.class);
        verify(repository, never()).saveIfNotExists(any());
        verify(guestQuota, never()).release(any());
    }

    // ============================================================
    // HISTORY
    // ============================================================

    @Test
    void freeHistoryOnlyListsLinksFromTheLastFiveDays() {
        String fiveDaysAgo = NOW.minusDays(5).format(ISO);
        when(repository.findByOwnerIdPaginated(1L, 20, null, false, fiveDaysAgo))
                .thenReturn(new UrlMappingDynamoRepository.DynamoPageResult<>(List.of(item(CODE, 0)), null));

        assertThat(service.getUrlsByUser(user, 20, null, false).getContent()).hasSize(1);
        verify(repository).countByOwnerId(1L, fiveDaysAgo);
    }

    @Test
    void paidHistoryListsAllLinks() {
        makePaid();
        when(repository.findByOwnerIdPaginated(eq(1L), anyInt(), any(), anyBoolean(), isNull()))
                .thenReturn(new UrlMappingDynamoRepository.DynamoPageResult<>(List.of(), null));

        service.getUrlsByUser(user, 20, null, false);

        verify(repository).countByOwnerId(eq(1L), isNull());
    }

    // ============================================================
    // UPDATE / DELETE
    // ============================================================

    @Test
    void updateWritesOnlyTheUrlAttributes() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.of(item(CODE, 7)));
        when(repository.updateOriginalUrl(eq(CODE), eq("https://new.example"),
                eq(UrlMappingItem.ownerUrlKey(1L, "https://new.example")), any())).thenReturn(true);

        UrlMappingDto dto = service.updateUrl(42L, "https://new.example", user);

        assertThat(dto.getOriginalUrl()).isEqualTo("https://new.example");
        assertThat(dto.getClickCount()).isEqualTo(7);
        verify(repository, never()).save(any());
    }

    @Test
    void missingOrForeignMappingIsNotFound() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUrl(42L, user))
                .isInstanceOf(UrlMappingNotFoundException.class);
    }

    @Test
    void expiredLinkCannotBeEdited() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.of(item(CODE, 0, NOW.minusDays(6))));

        assertThatThrownBy(() -> service.updateUrl(42L, "https://new.example", user))
                .isInstanceOf(UrlMappingNotFoundException.class);
    }

    // ============================================================
    // ANALYTICS
    // ============================================================

    @Test
    void freeUsersHaveNoAnalytics() {
        assertThatThrownBy(() -> service.getDashboard(user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("Monthly and Yearly");
        assertThatThrownBy(() -> service.getTopUrls(user, 5))
                .isInstanceOf(LimitExceededException.class);
        verifyNoInteractions(analyticsStorage);
    }

    @Test
    void topUrlsByDateAreOrderedAndCountedForTheRange() {
        makePaid();
        when(repository.findByOwnerId(1L)).thenReturn(List.of(item("aaaaaaaa", 100), item("bbbbbbbb", 1)));
        when(analyticsStorage.countPerShortUrlAndDateBetween(any(), any(), any()))
                .thenReturn(Map.of("bbbbbbbb", 5L));

        List<UrlMappingDto> top = service.getTopUrlsByDate(user, 10, LocalDate.now(), LocalDate.now());

        assertThat(top).extracting(UrlMappingDto::getShortUrl).containsExactly("bbbbbbbb", "aaaaaaaa");
        assertThat(top).extracting(UrlMappingDto::getClickCount).containsExactly(5, 0);
    }

    @Test
    void activeUrlCountUsesThePlansWindow() {
        when(repository.countByOwnerId(anyLong(), any())).thenReturn(3L);

        assertThat(service.countActiveUrls(user)).isEqualTo(3);
        verify(repository).countByOwnerId(1L, NOW.minusDays(5).format(ISO));
    }
}
