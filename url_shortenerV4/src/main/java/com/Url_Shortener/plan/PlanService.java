package com.Url_Shortener.plan;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.Url_Shortener.resilience.Breakers;
import com.Url_Shortener.resilience.Guarded;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Decides which plan is in effect for a user, and when links expire.
 *
 * <p>Subscription timeline: a paid plan stays in effect until GRACE_PERIOD
 * after the end of the paid period. Then the user is FREE, and the links
 * they made while paid keep working for the FREE link lifetime (5 days)
 * counted from that moment. After that they are expired for good: paying
 * again does not bring them back.
 *
 * <p>A link's expiry is stored on the link when it is known up front (links
 * made on FREE or as a guest) or fixed by a plan change. When a paid plan
 * lapses on its own there is no event to store it, so it is computed here
 * from the owner's subscription.
 *
 * <p>Expired links stop working and are hidden at once, but their data is
 * kept for EXPIRED_DATA_RETENTION more (30 days after their 5-day countdown
 * started) and then deleted by LinkCleanupJob.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final Duration GRACE_PERIOD = Duration.ofDays(7);

    /** How long an expired link's data is kept after it stops working. */
    public static final Duration EXPIRED_DATA_RETENTION = Duration.ofDays(25);

    /** Owner subscriptions are cached briefly for the redirect hot path. */
    private static final Duration OWNER_CACHE_TTL = Duration.ofMinutes(10);
    private static final String OWNER_CACHE_PREFIX = "plan:";

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Guarded redis;

    @Autowired
    public PlanService(UserRepository userRepository, StringRedisTemplate redisTemplate, Clock clock,
                       CircuitBreakerRegistry breakers) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.redis = new Guarded(breakers.circuitBreaker(Breakers.REDIS), log);
    }

    /** With a breaker of its own; for tests. */
    public PlanService(UserRepository userRepository, StringRedisTemplate redisTemplate, Clock clock) {
        this(userRepository, redisTemplate, clock, CircuitBreakerRegistry.ofDefaults());
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ============================================================
    // EFFECTIVE PLAN
    // ============================================================

    public OwnerStatus status(User user) {
        return status(user.getPlan(), user.getPlanPeriodEnd());
    }

    OwnerStatus status(Plan subscribed, LocalDateTime periodEnd) {
        Plan plan = subscribed != null ? subscribed : Plan.FREE;
        Plan effective = plan.isPaid() && periodEnd != null && now().isBefore(periodEnd.plus(GRACE_PERIOD))
                ? plan : Plan.FREE;
        return new OwnerStatus(effective, plan, plan.isPaid() ? periodEnd : null);
    }

    public Plan effectivePlan(User user) {
        return status(user).effective();
    }

    /** True while the paid period has ended but the grace period has not. */
    public boolean inGracePeriod(User user) {
        LocalDateTime end = user.getPlanPeriodEnd();
        if (user.getPlan() == null || !user.getPlan().isPaid() || end == null) {
            return false;
        }
        LocalDateTime now = now();
        return !now.isBefore(end) && now.isBefore(end.plus(GRACE_PERIOD));
    }

    /** End of the grace period, or null when not subscribed to a paid plan. */
    public LocalDateTime graceEndsAt(User user) {
        if (user.getPlan() == null || !user.getPlan().isPaid() || user.getPlanPeriodEnd() == null) {
            return null;
        }
        return user.getPlanPeriodEnd().plus(GRACE_PERIOD);
    }

    /** Start of the paid period that per-period link limits count; null when not paid. */
    public LocalDateTime periodStart(OwnerStatus status) {
        return switch (status.effective()) {
            case MONTHLY -> status.periodEnd().minusMonths(1);
            case YEARLY -> status.periodEnd().minusYears(1);
            default -> null;
        };
    }

    /**
     * The subscription of a link's owner; GUEST when the link has no owner.
     * Reads a short-lived Redis copy and falls back to Postgres, so plan
     * changes reach redirects within OWNER_CACHE_TTL at most (immediately
     * when changed through SubscriptionService, which evicts it).
     */
    public OwnerStatus ownerStatus(Long ownerId) {
        if (ownerId == null) {
            return OwnerStatus.GUEST;
        }
        String key = OWNER_CACHE_PREFIX + ownerId;
        String cached = redis.callOr("Redis plan lookup for owner " + ownerId,
                () -> redisTemplate.opsForValue().get(key), null);
        if (cached != null) {
            return decode(cached);
        }

        User owner = userRepository.findById(ownerId).orElse(null);
        Plan subscribed = owner != null ? owner.getPlan() : Plan.FREE;
        LocalDateTime periodEnd = owner != null ? owner.getPlanPeriodEnd() : null;
        redis.runOr("Redis plan cache write for owner " + ownerId,
                () -> redisTemplate.opsForValue().set(key, encode(subscribed, periodEnd), OWNER_CACHE_TTL));
        return status(subscribed, periodEnd);
    }

    /** Best effort: a stale copy otherwise expires within OWNER_CACHE_TTL. */
    public void evictOwner(Long ownerId) {
        redis.runOr("Redis plan cache evict for owner " + ownerId,
                () -> redisTemplate.delete(OWNER_CACHE_PREFIX + ownerId));
    }

    private static String encode(Plan plan, LocalDateTime periodEnd) {
        Plan p = plan != null ? plan : Plan.FREE;
        return p.name() + "|" + (periodEnd != null ? periodEnd.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
    }

    private OwnerStatus decode(String value) {
        int sep = value.indexOf('|');
        Plan plan = Plan.valueOf(value.substring(0, sep));
        String end = value.substring(sep + 1);
        return status(plan, end.isEmpty() ? null : LocalDateTime.parse(end));
    }

    // ============================================================
    // LINK EXPIRY
    // ============================================================

    /**
     * When a link stops working; null while it has no end.
     *
     * @param created     the link's creation time
     * @param storedExpiry the expiry stored on the link, if any; it always wins
     * @param owner       the owner's subscription now
     */
    public LocalDateTime linkExpiresAt(LocalDateTime created, LocalDateTime storedExpiry, OwnerStatus owner) {
        if (storedExpiry != null) {
            return storedExpiry;
        }
        Duration lifetime = owner.effective().linkLifetime();
        if (lifetime == null) {
            return null;
        }
        LocalDateTime lapsedAt = owner.lapsedAt();
        if (lapsedAt != null) {
            // Made while paid: the countdown starts when the plan lapsed.
            LocalDateTime start = created == null || created.isBefore(lapsedAt) ? lapsedAt : created;
            return start.plus(lifetime);
        }
        // Links made before expiry was stored on links.
        return created != null ? created.plus(lifetime) : null;
    }

    public boolean isActive(LocalDateTime expiresAt) {
        return expiresAt == null || now().isBefore(expiresAt);
    }

    /** When an expired link's data is deleted; null if it never expires. */
    public LocalDateTime dataDeletedAt(LocalDateTime expiresAt) {
        return expiresAt != null ? expiresAt.plus(EXPIRED_DATA_RETENTION) : null;
    }

    /**
     * Expiry to store on a link created now under plan; null if none.
     */
    public LocalDateTime newLinkExpiresAt(Plan plan) {
        return plan.linkLifetime() != null ? now().plus(plan.linkLifetime()) : null;
    }

    /**
     * Only links created after this can still be working for this owner;
     * null when older links can be too (paid, or within 5 days of a lapse).
     * Used to narrow list and count queries; expired links among the rest are
     * filtered by their stored expiry.
     */
    public LocalDateTime activeLinksCreatedAfter(OwnerStatus owner) {
        Duration lifetime = owner.effective().linkLifetime();
        if (lifetime == null) {
            return null;
        }
        LocalDateTime lapsedAt = owner.lapsedAt();
        if (lapsedAt != null && now().isBefore(lapsedAt.plus(lifetime))) {
            return null;
        }
        return now().minus(lifetime);
    }
}
