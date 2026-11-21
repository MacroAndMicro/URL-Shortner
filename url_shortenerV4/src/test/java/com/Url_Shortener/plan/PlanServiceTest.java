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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void subscribe(Plan plan, LocalDateTime periodEnd) {
        user.setPlan(plan);
        user.setPlanPeriodEnd(periodEnd);
    }

    @Test
    void newUsersAreFree() {
        assertThat(planService.effectivePlan(user)).isEqualTo(Plan.FREE);
        assertThat(planService.inGracePeriod(user)).isFalse();
        assertThat(planService.graceEndsAt(user)).isNull();
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

        assertThat(planService.effectivePlan(user)).isEqualTo(Plan.FREE);
        assertThat(planService.inGracePeriod(user)).isFalse();
    }

    @Test
    void ownerlessLinksAreGuestLinks() {
        assertThat(planService.effectivePlanForOwner(null)).isEqualTo(Plan.GUEST);
        verifyNoInteractions(userRepository);
    }

    @Test
    void ownerPlanIsReadFromTheDatabaseAndCached() {
        subscribe(Plan.MONTHLY, NOW.plusDays(3));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(planService.effectivePlanForOwner(7L)).isEqualTo(Plan.MONTHLY);
        verify(values).set(eq("plan:7"), eq("MONTHLY|2026-11-24T12:00:00"), any(Duration.class));
    }

    @Test
    void cachedOwnerPlanStillHonoursTheGracePeriod() {
        when(values.get("plan:7")).thenReturn("YEARLY|2026-11-01T00:00:00");

        // Period ended 20 days ago: grace is over, so FREE despite the cached YEARLY.
        assertThat(planService.effectivePlanForOwner(7L)).isEqualTo(Plan.FREE);
        verifyNoInteractions(userRepository);
    }

    @Test
    void redisFailureFallsBackToTheDatabase() {
        when(values.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(planService.effectivePlanForOwner(7L)).isEqualTo(Plan.FREE);
    }

    @Test
    void changingTheSubscriptionEvictsTheCachedOwnerPlan() {
        planService.changeSubscription(user, Plan.YEARLY, NOW.plusYears(1));

        assertThat(user.getPlan()).isEqualTo(Plan.YEARLY);
        verify(redis).delete("plan:7");
    }

    @Test
    void downgradingToFreeClearsThePeriodEnd() {
        subscribe(Plan.MONTHLY, NOW.plusDays(3));

        planService.changeSubscription(user, Plan.FREE, NOW.plusDays(3));

        assertThat(user.getPlanPeriodEnd()).isNull();
    }

    @Test
    void invalidSubscriptionsAreRejected() {
        assertThatThrownBy(() -> planService.changeSubscription(user, Plan.GUEST, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planService.changeSubscription(user, Plan.MONTHLY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkExpiryFollowsThePlan() {
        LocalDateTime created = NOW.minusDays(4);

        assertThat(planService.linkExpiresAt(created, Plan.FREE)).isEqualTo(created.plusDays(5));
        assertThat(planService.isLinkActive(created, Plan.FREE)).isTrue();
        assertThat(planService.isLinkActive(NOW.minusDays(5), Plan.FREE)).isFalse();
        assertThat(planService.linkExpiresAt(created, Plan.YEARLY)).isNull();
        assertThat(planService.isLinkActive(NOW.minusYears(3), Plan.YEARLY)).isTrue();
        assertThat(planService.activeLinksCreatedAfter(Plan.FREE)).isEqualTo(NOW.minusDays(5));
        assertThat(planService.activeLinksCreatedAfter(Plan.MONTHLY)).isNull();
    }
}
