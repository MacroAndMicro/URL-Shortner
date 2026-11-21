package com.Url_Shortener.plan;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlanServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);

    private UserRepository userRepository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private PlanService planService;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        planService = new PlanService(userRepository, redis,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        user = new User();
        user.setId(7L);
    }

    private void subscribe(Plan plan, LocalDateTime periodEnd) {
        user.setPlan(plan);
        user.setPlanPeriodEnd(periodEnd);
    }

    // ============================================================
    // EFFECTIVE PLAN AND GRACE PERIOD
    // ============================================================

    @Test
    void newUsersAreFree() {
        assertThat(planService.effectivePlan(user)).isEqualTo(Plan.FREE);
        assertThat(planService.inGracePeriod(user)).isFalse();
        assertThat(planService.graceEndsAt(user)).isNull();
        assertThat(planService.status(user).lapsedAt()).isNull();
    }

    @Test
    void paidPlanAppliesDuringThePaidPeriod() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        assertThat(planService.effectivePlan(user)).isEqualTo(Plan.YEARLY);
        assertThat(planService.inGracePeriod(user)).isFalse();
    }

    @Test
    void paidPlanStillAppliesForSevenDaysAfterThePeriodEnds() {
        subscribe(Plan.MONTHLY, NOW.minusDays(6).minusHours(23));

        assertThat(planService.effectivePlan(user)).isEqualTo(Plan.MONTHLY);
        assertThat(planService.inGracePeriod(user)).isTrue();
        assertThat(planService.graceEndsAt(user)).isEqualTo(user.getPlanPeriodEnd().plusDays(7));
    }

    @Test
    void userFallsBackToFreeWhenTheGracePeriodEnds() {
        subscribe(Plan.MONTHLY, NOW.minusDays(7));

        OwnerStatus status = planService.status(user);
        assertThat(status.effective()).isEqualTo(Plan.FREE);
        assertThat(status.subscribed()).isEqualTo(Plan.MONTHLY);
        assertThat(status.lapsedAt()).isEqualTo(NOW);
    }

    @Test
    void periodStartIsOneMonthOrOneYearBeforeThePeriodEnd() {
        subscribe(Plan.MONTHLY, NOW.plusDays(10));
        assertThat(planService.periodStart(planService.status(user))).isEqualTo(NOW.plusDays(10).minusMonths(1));

        subscribe(Plan.YEARLY, NOW.plusDays(10));
        assertThat(planService.periodStart(planService.status(user))).isEqualTo(NOW.plusDays(10).minusYears(1));

        subscribe(Plan.FREE, null);
        assertThat(planService.periodStart(planService.status(user))).isNull();
    }

    // ============================================================
    // OWNER LOOKUP FOR REDIRECTS
    // ============================================================

    @Test
    void ownerlessLinksAreGuestLinks() {
        assertThat(planService.ownerStatus(null)).isEqualTo(OwnerStatus.GUEST);
        verifyNoInteractions(userRepository);
    }

    @Test
    void ownerPlanIsReadFromTheDatabaseAndCached() {
        subscribe(Plan.MONTHLY, NOW.plusDays(3));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(planService.ownerStatus(7L).effective()).isEqualTo(Plan.MONTHLY);
        verify(values).set(eq("plan:7"), eq("MONTHLY|2026-11-24T12:00:00"), any(Duration.class));
    }

    @Test
    void cachedOwnerPlanStillHonoursTheGracePeriod() {
        when(values.get("plan:7")).thenReturn("YEARLY|2026-11-01T00:00:00");

        // Period ended 20 days ago: grace is over, so FREE despite the cached YEARLY.
        OwnerStatus status = planService.ownerStatus(7L);
        assertThat(status.effective()).isEqualTo(Plan.FREE);
        assertThat(status.lapsedAt()).isEqualTo(LocalDateTime.of(2026, 11, 8, 0, 0));
        verifyNoInteractions(userRepository);
    }

    @Test
    void redisFailureFallsBackToTheDatabase() {
        when(values.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(planService.ownerStatus(7L).effective()).isEqualTo(Plan.FREE);
    }

    // ============================================================
    // LINK EXPIRY
    // ============================================================

    @Test
    void storedExpiryAlwaysWins() {
        LocalDateTime stored = NOW.minusDays(1);
        OwnerStatus yearly = new OwnerStatus(Plan.YEARLY, Plan.YEARLY, NOW.plusYears(1));

        assertThat(planService.linkExpiresAt(NOW.minusDays(9), stored, yearly)).isEqualTo(stored);
    }

    @Test
    void paidLinksWithoutStoredExpiryNeverExpire() {
        OwnerStatus monthly = new OwnerStatus(Plan.MONTHLY, Plan.MONTHLY, NOW.plusDays(10));

        assertThat(planService.linkExpiresAt(NOW.minusYears(3), null, monthly)).isNull();
    }

    @Test
    void afterALapseOldLinksGetFiveDaysFromTheLapse() {
        // Grace ended 2 days ago.
        OwnerStatus lapsed = new OwnerStatus(Plan.FREE, Plan.MONTHLY, NOW.minusDays(9));
        LocalDateTime lapsedAt = NOW.minusDays(2);

        assertThat(planService.linkExpiresAt(NOW.minusYears(1), null, lapsed)).isEqualTo(lapsedAt.plusDays(5));
        // A link made after the lapse counts from its own creation.
        assertThat(planService.linkExpiresAt(NOW.minusDays(1), null, lapsed)).isEqualTo(NOW.plusDays(4));
    }

    @Test
    void linksMadeBeforeExpiryWasStoredCountFromCreation() {
        OwnerStatus free = new OwnerStatus(Plan.FREE, Plan.FREE, null);

        assertThat(planService.linkExpiresAt(NOW.minusDays(1), null, free)).isEqualTo(NOW.plusDays(4));
        assertThat(planService.linkExpiresAt(NOW.minusDays(1), null, OwnerStatus.GUEST)).isEqualTo(NOW.plusDays(4));
    }

    @Test
    void expiredLinkDataIsKeptThirtyDaysFromTheStartOfItsCountdown() {
        LocalDateTime expiresAt = NOW.plusDays(5);

        assertThat(planService.dataDeletedAt(expiresAt)).isEqualTo(NOW.plusDays(30));
        assertThat(planService.dataDeletedAt(null)).isNull();
    }

    @Test
    void newLinkExpiryFollowsThePlan() {
        assertThat(planService.newLinkExpiresAt(Plan.FREE)).isEqualTo(NOW.plusDays(5));
        assertThat(planService.newLinkExpiresAt(Plan.GUEST)).isEqualTo(NOW.plusDays(5));
        assertThat(planService.newLinkExpiresAt(Plan.YEARLY)).isNull();
    }

    @Test
    void workingLinksQueryWindowCoversALapsedUsersOldLinks() {
        assertThat(planService.activeLinksCreatedAfter(new OwnerStatus(Plan.FREE, Plan.FREE, null)))
                .isEqualTo(NOW.minusDays(5));
        assertThat(planService.activeLinksCreatedAfter(new OwnerStatus(Plan.MONTHLY, Plan.MONTHLY, NOW.plusDays(1))))
                .isNull();
        // Lapsed 2 days ago: older links still work, so no window.
        assertThat(planService.activeLinksCreatedAfter(new OwnerStatus(Plan.FREE, Plan.MONTHLY, NOW.minusDays(9))))
                .isNull();
        // Lapsed 6 days ago: they have all expired.
        assertThat(planService.activeLinksCreatedAfter(new OwnerStatus(Plan.FREE, Plan.MONTHLY, NOW.minusDays(13))))
                .isEqualTo(NOW.minusDays(5));
    }
}
