package com.Url_Shortener.plan;

import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.security.IpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class GuestQuotaServiceTest {

    private static final String GUEST = "3f2b8c1e-9d4a-4b7e-8f21-6a5c0d9e1b23";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private GuestQuotaService quota;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        quota = new GuestQuotaService(redis, new IpHasher("salt"),
                Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneId.of("UTC")), 10);
    }

    @Test
    void firstLinkReservesTheBrowserSlotForTheLinkLifetime() {
        quota.acquire(GUEST, "1.2.3.4");

        verify(values).setIfAbsent(startsWith("guest:browser:"), eq("1"), eq(Duration.ofDays(5)));
    }

    @Test
    void secondLinkFromTheSameBrowserIsRefused() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> quota.acquire(GUEST, "1.2.3.4"))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).contains("Sign up");
                });
    }

    @Test
    void ipOverTheDailyCapIsRefusedBeforeTheBrowserSlotIsTaken() {
        when(values.increment(anyString())).thenReturn(11L);

        assertThatThrownBy(() -> quota.acquire(GUEST, "1.2.3.4"))
                .isInstanceOfSatisfying(LimitExceededException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void ipKeyIsPerDayAndDoesNotContainTheRawAddress() {
        quota.acquire(GUEST, "1.2.3.4");

        verify(values).increment(argThat((String key) ->
                key.startsWith("guest:ip:") && key.endsWith(":2026-11-21") && !key.contains("1.2.3.4")));
        verify(redis).expire(startsWith("guest:ip:"), eq(Duration.ofDays(2)));
    }

    @Test
    void browserIdMustBeAUuid() {
        assertThatThrownBy(() -> quota.acquire("not-a-uuid", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quota.acquire(null, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(values);
    }

    @Test
    void redisDownRefusesGuestLinks() {
        when(values.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> quota.acquire(GUEST, "1.2.3.4"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void releaseFreesTheBrowserSlot() {
        quota.release(GUEST);

        verify(redis).delete(startsWith("guest:browser:"));
    }
}
