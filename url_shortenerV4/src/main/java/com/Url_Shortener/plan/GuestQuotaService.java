package com.Url_Shortener.plan;

import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.security.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Limits for users without an account, which need a way to recognise the
 * same guest coming back:
 *
 * <ul>
 *   <li>Per browser: the frontend sends a random ID it keeps in local
 *       storage (X-Guest-Id). One active link per ID; the slot frees up when
 *       that link expires. Clearing storage gets around it, which is what
 *       the IP limit is for.</li>
 *   <li>Per IP address: a looser daily cap, so clearing storage repeatedly
 *       does not give unlimited links, without blocking a whole office or
 *       mobile network after one person's link.</li>
 * </ul>
 *
 * Counts live in Redis. If Redis is down, guest creation is refused rather
 * than allowed without limits; signed-in users are unaffected.
 */
@Service
public class GuestQuotaService {

    private static final Logger log = LoggerFactory.getLogger(GuestQuotaService.class);

    private static final Pattern GUEST_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final StringRedisTemplate redisTemplate;
    private final IpHasher ipHasher;
    private final Clock clock;
    private final long ipDailyLimit;

    public GuestQuotaService(StringRedisTemplate redisTemplate, IpHasher ipHasher, Clock clock,
                             @Value("${app.guest.ip-daily-limit:10}") long ipDailyLimit) {
        this.redisTemplate = redisTemplate;
        this.ipHasher = ipHasher;
        this.clock = clock;
        this.ipDailyLimit = ipDailyLimit;
    }

    public static void validateGuestId(String guestId) {
        if (guestId == null || !GUEST_ID.matcher(guestId).matches()) {
            throw new IllegalArgumentException("X-Guest-Id header must be a UUID");
        }
    }

    /**
     * Reserves this guest's link slot, or throws if a limit is reached.
     * Call release() if the link is then not created.
     */
    public void acquire(String guestId, String ipAddress) {
        validateGuestId(guestId);
        try {
            String ipKey = "guest:ip:" + ipHasher.hash(ipAddress) + ":" + LocalDate.now(clock);
            Long count = redisTemplate.opsForValue().increment(ipKey);
            if (count != null && count == 1) {
                redisTemplate.expire(ipKey, Duration.ofDays(2));
            }
            if (count != null && count > ipDailyLimit) {
                throw LimitExceededException.tooMany(
                        "Too many guest links from your network today. Sign up free to create more.");
            }

            Boolean reserved = redisTemplate.opsForValue()
                    .setIfAbsent(browserKey(guestId), "1", Plan.GUEST.linkLifetime());
            if (!Boolean.TRUE.equals(reserved)) {
                throw LimitExceededException.forbidden(
                        "Guests can have " + Plan.GUEST.linkLimit()
                                + " active link at a time. Sign up free to create more.");
            }
        } catch (LimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Guest quota check failed: {}", e.getMessage());
            throw new ServiceUnavailableException(
                    "Guest links are temporarily unavailable. Please sign in or try again later.");
        }
    }

    /** Frees the slot taken by acquire() when link creation failed. */
    public void release(String guestId) {
        try {
            redisTemplate.delete(browserKey(guestId));
        } catch (Exception e) {
            log.warn("Could not release guest slot: {}", e.getMessage());
        }
    }

    private static String browserKey(String guestId) {
        // Hashed so the raw browser ID is never stored.
        return "guest:browser:" + Sha256.hex(guestId.toLowerCase());
    }
}
