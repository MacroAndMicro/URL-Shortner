package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.exception.ConflictException;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
                new SnowflakeIdGenerator(0), planService, guestQuota, userRepository);

        user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.reservePeriodLink(anyLong(), any(), anyInt())).thenReturn(1);
        when(repository.findByOwnerAndOriginalUrl(any(), any())).thenReturn(List.of());
        when(repository.saveIfNotExists(any())).thenReturn(true);
    }

    private void subscribe(Plan plan, LocalDateTime periodEnd) {
        user.setPlan(plan);
        user.setPlanPeriodEnd(periodEnd);
    }

    private void makeMonthly() {
        subscribe(Plan.MONTHLY, NOW.plusDays(10));
    }

    private UrlMappingItem item(String shortUrl, int clicks, LocalDateTime created, LocalDateTime expiresAt) {
        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(shortUrl);
        item.setId(42L);
        item.setOriginalUrl(TARGET);
        item.setOwnerId(1L);
        item.setClickCount(clicks);
        item.setCreatedDate(created.format(ISO));
        item.setExpiresAt(expiresAt != null ? expiresAt.format(ISO) : null);
        return item;
    }

    /** A link made a day ago on the Free plan. */
    private UrlMappingItem freeItem(String shortUrl, int clicks) {
        return item(shortUrl, clicks, NOW.minusDays(1), NOW.plusDays(4));
    }

    /** A link made a day ago on a paid plan. */
    private UrlMappingItem paidItem(String shortUrl, int clicks) {
        return item(shortUrl, clicks, NOW.minusDays(1), null);
    }

    private void cached(UrlMappingItem item) {
        when(cache.get(item.getShortUrl())).thenReturn(Optional.of(CachedUrlData.from(item)));
    }

    private UrlMappingItem savedItem() {
        ArgumentCaptor<UrlMappingItem> saved = ArgumentCaptor.forClass(UrlMappingItem.class);
        verify(repository).saveIfNotExists(saved.capture());
        return saved.getValue();
    }

    // ============================================================
    // REDIRECT
    // ============================================================

    @Test
    void paidLinkRedirectsWith302() {
        makeMonthly();
        cached(paidItem(CODE, 0));

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();

        assertThat(target.originalUrl()).isEqualTo(TARGET);
        assertThat(target.permanent()).isFalse();
        assertThat(target.maxAgeSeconds()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void freeLinkRedirectsWith301CachedUntilItExpires() {
        cached(freeItem(CODE, 0));

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();

        assertThat(target.permanent()).isTrue();
        assertThat(target.maxAgeSeconds()).isEqualTo(4 * 24 * 3600L);
    }

    @Test
    void clicksAreRecordedOnEveryPlan() {
        UrlMappingItem guestItem = item(CODE, 0, NOW.minusHours(1), NOW.plusDays(4));
        guestItem.setOwnerId(null);
        cached(guestItem);
        cached(freeItem("free000001", 0));

        service.resolve(CODE, "1.2.3.4", null, "agent");
        service.resolve("free000001", "1.2.3.4", null, "agent");

        verify(publisher, times(2)).publish(any());
    }

    @Test
    void expiredLinkIsNotFoundButNotMarkedMissing() {
        cached(item(CODE, 0, NOW.minusDays(5), NOW));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
        verify(cache, never()).markMissing(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void linkThatExpiredBeforeAnUpgradeStaysExpired() {
        makeMonthly();
        cached(item(CODE, 0, NOW.minusDays(9), NOW.minusDays(4)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
    }

    @Test
    void lapsedPlanGivesOldLinksFiveDaysFromTheLapse() {
        // Paid period ended 10 days ago: grace ended 3 days ago, so 2 days are left.
        subscribe(Plan.YEARLY, NOW.minusDays(10));
        cached(item(CODE, 0, NOW.minusDays(300), null));

        RedirectTarget target = service.resolve(CODE, "1.2.3.4", null, "agent").orElseThrow();
        assertThat(target.permanent()).isTrue();
        assertThat(target.maxAgeSeconds()).isEqualTo(2 * 24 * 3600L);

        subscribe(Plan.YEARLY, NOW.minusDays(12)); // grace ended 5 days ago
        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isEmpty();
    }

    @Test
    void subscriptionInGracePeriodKeepsPaidRules() {
        subscribe(Plan.MONTHLY, NOW.minusDays(6));
        cached(item(CODE, 0, NOW.minusDays(30), null));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent"))
                .hasValueSatisfying(t -> assertThat(t.permanent()).isFalse());
    }

    @Test
    void cacheMissReadsDynamoDbAndFillsCacheWithoutIncrementing() {
        when(cache.get(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(CODE)).thenReturn(Optional.of(freeItem(CODE, 0)));

        assertThat(service.resolve(CODE, "1.2.3.4", null, "agent")).isPresent();

        verify(cache).put(eq(CODE), any());
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void unknownCodeIsMarkedMissingAndThenSkipsDynamoDb() {
        String code = "abcdefgh12"; // all lower case, so no second lookup
        when(cache.get(code)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(code)).thenReturn(Optional.empty());

        assertThat(service.resolve(code, "1.2.3.4", null, "agent")).isEmpty();
        verify(cache).markMissing(code);
        verify(publisher, never()).publish(any());

        reset(repository);
        when(cache.isMarkedMissing(code)).thenReturn(true);

        assertThat(service.resolve(code, "1.2.3.4", null, "agent")).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void customCodeIsFoundWhateverCaseItIsTypedIn() {
        when(cache.get(any())).thenReturn(Optional.empty());
        when(repository.findByShortUrl("My-Link")).thenReturn(Optional.empty());
        UrlMappingItem custom = paidItem("my-link", 0);
        when(repository.findByShortUrl("my-link")).thenReturn(Optional.of(custom));
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        assertThat(service.resolve("My-Link", "1.2.3.4", null, "agent")).isPresent();

        ArgumentCaptor<ClickEventCreatedEvent> event = ArgumentCaptor.forClass(ClickEventCreatedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().getShortUrl()).isEqualTo("my-link");
    }

    @Test
    void publishedEventContainsHashedIpNotTheRawAddress() {
        cached(freeItem(CODE, 0));

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
        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        assertThat(dto.getShortUrl()).matches("[0-9A-Za-z]{10,11}");
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void freeLinkStoresItsFiveDayExpiry() {
        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        assertThat(dto.getExpiresAt()).isEqualTo(NOW.plusDays(5));
        assertThat(savedItem().getExpiresAt()).isEqualTo(NOW.plusDays(5).format(ISO));
    }

    @Test
    void paidLinkHasNoExpiry() {
        makeMonthly();

        assertThat(service.createShortUrl(TARGET, user).getExpiresAt()).isNull();
        assertThat(savedItem().getExpiresAt()).isNull();
    }

    @Test
    void createStoresTheDuplicateLookupKeyWithoutLoadingTheUsersUrls() {
        service.createShortUrl(TARGET, user);

        assertThat(savedItem().getOwnerUrlKey())
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
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void createFailsAfterRepeatedCollisions() {
        when(repository.saveIfNotExists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createReturnsExistingWorkingLinkForSameUrl() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(List.of(freeItem(CODE, 3)));

        assertThat(service.createShortUrl(TARGET, user).getShortUrl()).isEqualTo(CODE);
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void createIgnoresAnExpiredDuplicate() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET))
                .thenReturn(List.of(item(CODE, 3, NOW.minusDays(6), NOW.minusDays(1))));

        assertThat(service.createShortUrl(TARGET, user).getShortUrl()).isNotEqualTo(CODE);
    }

    @Test
    void freeUserAtFiftyWorkingLinksCannotCreateMore() {
        String fiveDaysAgo = NOW.minusDays(5).format(ISO);
        when(repository.countByOwnerId(1L, fiveDaysAgo, NOW.format(ISO))).thenReturn(50L);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).contains("50").contains("Free");
                });
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void lapsedUserCountsTheirStillWorkingPaidLinksAgainstTheFreeLimit() {
        subscribe(Plan.MONTHLY, NOW.minusDays(9)); // grace ended 2 days ago
        when(repository.countByOwnerId(eq(1L), isNull(), eq(NOW.format(ISO)))).thenReturn(120L);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("50");
    }

    @Test
    void monthlyLimitCountsLinksCreatedThisPeriod() {
        makeMonthly();
        when(userRepository.reservePeriodLink(1L, NOW.plusDays(10).minusMonths(1), 150)).thenReturn(0);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("150 links this month");
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void yearlyLimitIsTwoThousandPerYear() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        service.createShortUrl(TARGET, user);

        verify(userRepository).reservePeriodLink(1L, NOW.plusDays(100).minusYears(1), 2000);
    }

    @Test
    void failedCreateGivesTheCountedLinkBack() {
        makeMonthly();
        when(repository.saveIfNotExists(any())).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user)).isInstanceOf(RuntimeException.class);
        verify(userRepository).releasePeriodLink(1L, NOW.plusDays(10).minusMonths(1));
    }

    @Test
    void returningAnExistingLinkDoesNotUseQuota() {
        makeMonthly();
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(List.of(paidItem(CODE, 0)));

        service.createShortUrl(TARGET, user);

        verify(userRepository, never()).reservePeriodLink(anyLong(), any(), anyInt());
    }

    // ============================================================
    // CUSTOM CODES
    // ============================================================

    @Test
    void yearlyUsersCanChooseTheCodeAndItIsStoredInLowerCase() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        UrlMappingDto dto = service.createShortUrl(TARGET, "My-Portfolio", user);

        assertThat(dto.getShortUrl()).isEqualTo("my-portfolio");
        UrlMappingItem saved = savedItem();
        assertThat(saved.getOwnerUrlKey()).isNull();
        verify(repository, never()).findByOwnerAndOriginalUrl(any(), any());
    }

    @Test
    void takenCustomCodeIsAConflictAndGivesTheQuotaBack() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));
        when(repository.saveIfNotExists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, "taken", user))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");
        verify(userRepository).releasePeriodLink(eq(1L), any());
    }

    @Test
    void otherPlansCannotChooseTheCode() {
        assertThatThrownBy(() -> service.createShortUrl(TARGET, "my-link", user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("Yearly");
        makeMonthly();
        assertThatThrownBy(() -> service.createShortUrl(TARGET, "my-link", user))
                .isInstanceOf(LimitExceededException.class);
        assertThatThrownBy(() -> service.createGuestShortUrl(TARGET, "my-link", "guest-id", "1.2.3.4"))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    void invalidCustomCodeIsRejectedBeforeAnythingIsCounted() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        assertThatThrownBy(() -> service.createShortUrl(TARGET, "admin", user))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).reservePeriodLink(anyLong(), any(), anyInt());
    }

    // ============================================================
    // CREATE (guest)
    // ============================================================

    @Test
    void guestLinkHasNoOwnerAndExpiresAfterFiveDays() {
        UrlMappingDto dto = service.createGuestShortUrl(TARGET, null, "guest-id", "1.2.3.4");

        verify(guestQuota).acquire("guest-id", "1.2.3.4");
        UrlMappingItem saved = savedItem();
        assertThat(saved.getOwnerId()).isNull();
        assertThat(saved.getOwnerUrlKey()).isNull();
        assertThat(dto.getExpiresAt()).isEqualTo(NOW.plusDays(5));
    }

    @Test
    void guestSlotIsReleasedWhenCreationFails() {
        when(repository.saveIfNotExists(any())).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> service.createGuestShortUrl(TARGET, null, "guest-id", "1.2.3.4"))
                .isInstanceOf(RuntimeException.class);
        verify(guestQuota).release("guest-id");
    }

    @Test
    void guestOverLimitCreatesNothing() {
        doThrow(LimitExceededException.forbidden("limit")).when(guestQuota).acquire(any(), any());

        assertThatThrownBy(() -> service.createGuestShortUrl(TARGET, null, "guest-id", "1.2.3.4"))
                .isInstanceOf(LimitExceededException.class);
        verify(repository, never()).saveIfNotExists(any());
        verify(guestQuota, never()).release(any());
    }

    // ============================================================
    // HISTORY
    // ============================================================

    @Test
    void freeHistoryOnlyListsWorkingLinksFromTheLastFiveDays() {
        String fiveDaysAgo = NOW.minusDays(5).format(ISO);
        String now = NOW.format(ISO);
        when(repository.findByOwnerIdPaginated(1L, 20, null, false, fiveDaysAgo, now))
                .thenReturn(new UrlMappingDynamoRepository.DynamoPageResult<>(List.of(freeItem(CODE, 0)), null));

        assertThat(service.getUrlsByUser(user, 20, null, false).getContent()).hasSize(1);
        verify(repository).countByOwnerId(1L, fiveDaysAgo, now);
    }

    @Test
    void paidHistoryListsAllWorkingLinks() {
        makeMonthly();
        when(repository.findByOwnerIdPaginated(eq(1L), anyInt(), any(), anyBoolean(), isNull(), any()))
                .thenReturn(new UrlMappingDynamoRepository.DynamoPageResult<>(List.of(), null));

        service.getUrlsByUser(user, 20, null, false);

        verify(repository).countByOwnerId(eq(1L), isNull(), eq(NOW.format(ISO)));
    }

    // ============================================================
    // UPDATE / DELETE
    // ============================================================

    @Test
    void freeLinkCanBeEditedWithinThreeHours() {
        UrlMappingItem generated = item(CODE, 7, NOW.minusHours(2), NOW.plusDays(4));
        generated.setOwnerUrlKey(UrlMappingItem.ownerUrlKey(1L, TARGET)); // marks a generated link
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.of(generated));
        when(repository.updateOriginalUrl(eq(CODE), eq("https://new.example"),
                eq(UrlMappingItem.ownerUrlKey(1L, "https://new.example")), any())).thenReturn(true);

        UrlMappingDto dto = service.updateUrl(42L, "https://new.example", user);

        assertThat(dto.getOriginalUrl()).isEqualTo("https://new.example");
        assertThat(dto.getClickCount()).isEqualTo(7);
        verify(repository, never()).save(any());
    }

    @Test
    void freeLinkCannotBeEditedAfterThreeHoursButCanBeDeleted() {
        when(repository.findByIdAndOwnerId(42L, 1L))
                .thenReturn(Optional.of(item(CODE, 0, NOW.minusHours(3), NOW.plusDays(4))));

        assertThatThrownBy(() -> service.updateUrl(42L, "https://new.example", user))
                .isInstanceOfSatisfying(LimitExceededException.class,
                        e -> assertThat(e.getMessage()).contains("3 hours").contains("delete"));

        service.deleteUrl(42L, user);
        verify(repository).deleteByShortUrl(CODE);
    }

    @Test
    void paidLinkCanBeEditedAnyTime() {
        makeMonthly();
        when(repository.findByIdAndOwnerId(42L, 1L))
                .thenReturn(Optional.of(item(CODE, 0, NOW.minusDays(200), null)));
        when(repository.updateOriginalUrl(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.updateUrl(42L, "https://new.example", user).getOriginalUrl())
                .isEqualTo("https://new.example");
    }

    @Test
    void editingACustomLinkKeepsItOutOfTheDuplicateCheck() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.of(paidItem("my-link", 0)));
        when(repository.updateOriginalUrl(any(), any(), any(), any())).thenReturn(true);

        service.updateUrl(42L, "https://new.example", user);

        verify(repository).updateOriginalUrl(eq("my-link"), eq("https://new.example"), isNull(), any());
    }

    @Test
    void missingOrForeignMappingIsNotFound() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUrl(42L, user))
                .isInstanceOf(UrlMappingNotFoundException.class);
    }

    @Test
    void expiredLinkCannotBeEdited() {
        when(repository.findByIdAndOwnerId(42L, 1L))
                .thenReturn(Optional.of(item(CODE, 0, NOW.minusDays(6), NOW.minusDays(1))));

        assertThatThrownBy(() -> service.updateUrl(42L, "https://new.example", user))
                .isInstanceOf(UrlMappingNotFoundException.class);
    }

    // ============================================================
    // ANALYTICS
    // ============================================================

    @Test
    void freeUsersAreNotShownAnalytics() {
        assertThatThrownBy(() -> service.getDashboard(user))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("Monthly and Yearly");
        assertThatThrownBy(() -> service.getTopUrls(user, 5))
                .isInstanceOf(LimitExceededException.class);
        verifyNoInteractions(analyticsStorage);
    }

    @Test
    void analyticsLeaveOutLinksThatExpiredBeforeAnUpgrade() {
        makeMonthly();
        when(repository.findByOwnerId(1L)).thenReturn(List.of(
                paidItem("aaaaaaaa", 100),
                item("bbbbbbbb", 50, NOW.minusDays(9), NOW.minusDays(4))));

        assertThat(service.getTopUrls(user, 10)).extracting(UrlMappingDto::getShortUrl).containsExactly("aaaaaaaa");
    }

    @Test
    void topUrlsByDateAreOrderedAndCountedForTheRange() {
        makeMonthly();
        when(repository.findByOwnerId(1L)).thenReturn(List.of(paidItem("aaaaaaaa", 100), paidItem("bbbbbbbb", 1)));
        when(analyticsStorage.countPerShortUrlAndDateBetween(any(), any(), any()))
                .thenReturn(Map.of("bbbbbbbb", 5L));

        List<UrlMappingDto> top = service.getTopUrlsByDate(user, 10, LocalDate.now(), LocalDate.now());

        assertThat(top).extracting(UrlMappingDto::getShortUrl).containsExactly("bbbbbbbb", "aaaaaaaa");
        assertThat(top).extracting(UrlMappingDto::getClickCount).containsExactly(5, 0);
    }

    @Test
    void linksCreatedThisPeriodOnlyCountsTheCurrentPeriod() {
        makeMonthly();
        user.setPeriodLinksStart(NOW.plusDays(10).minusMonths(1));
        user.setPeriodLinksUsed(12);
        assertThat(service.linksCreatedThisPeriod(user)).isEqualTo(12);

        user.setPeriodLinksStart(NOW.plusDays(10).minusMonths(2));
        assertThat(service.linksCreatedThisPeriod(user)).isZero();
    }
}
