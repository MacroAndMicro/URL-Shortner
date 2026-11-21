package com.Url_Shortener.plan;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Decides which plan is in effect for a user and when links expire.
 *
 * A paid plan stays in effect until GRACE_PERIOD after the end of the paid
 * period; after that the user is FREE. This is computed whenever it is
 * needed, so a lapsed subscription takes effect without a scheduled job.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public static final Duration GRACE_PERIOD = Duration.ofDays(7);

    /** Owner subscriptions are cached briefly for the redirect hot path. */
    private static final Duration OWNER_CACHE_TTL = Duration.ofMinutes(10);
    private static final String OWNER_CACHE_PREFIX = "plan:";

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public PlanService(UserRepository userRepository, StringRedisTemplate redisTemplate, Clock clock) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ============================================================
    // EFFECTIVE PLAN
    // ============================================================

    public Plan effectivePlan(User user) {
        return effectivePlan(user.getPlan(), user.getPlanPeriodEnd());
    }

    Plan effectivePlan(Plan subscribed, LocalDateTime periodEnd) {
        if (subscribed == null || !subscribed.isPaid() || periodEnd == null) {
            return Plan.FREE;
        }
        return now().isBefore(periodEnd.plus(GRACE_PERIOD)) ? subscribed : Plan.FREE;
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

    /** End of the grace period, or null when not on a paid plan. */
    public LocalDateTime graceEndsAt(User user) {
        if (user.getPlan() == null || !user.getPlan().isPaid() || user.getPlanPeriodEnd() == null) {
            return null;
        }
        return user.getPlanPeriodEnd().plus(GRACE_PERIOD);
    }

    /**
     * The plan in effect for a link's owner; GUEST when the link has no owner.
     * Reads a short-lived Redis copy of the subscription and falls back to
     * Postgres, so plan changes reach redirects within OWNER_CACHE_TTL at most
     * (immediately when changed through changeSubscription).
     */
    public Plan effectivePlanForOwner(Long ownerId) {
        if (ownerId == null) {
            return Plan.GUEST;
        }
        String key = OWNER_CACHE_PREFIX + ownerId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return decode(cached);
            }
        } catch (Exception e) {
            log.warn("Redis plan lookup failed for owner {}: {}", ownerId, e.getMessage());
        }

        User owner = userRepository.findById(ownerId).orElse(null);
        Plan subscribed = owner != null ? owner.getPlan() : Plan.FREE;
        LocalDateTime periodEnd = owner != null ? owner.getPlanPeriodEnd() : null;
        try {
            redisTemplate.opsForValue().set(key, encode(subscribed, periodEnd), OWNER_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis plan cache write failed for owner {}: {}", ownerId, e.getMessage());
        }
        return effectivePlan(subscribed, periodEnd);
    }

    private static String encode(Plan plan, LocalDateTime periodEnd) {
        return plan.name() + "|" + (periodEnd != null ? periodEnd.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
    }

    private Plan decode(String value) {
        int sep = value.indexOf('|');
        Plan plan = Plan.valueOf(value.substring(0, sep));
        String end = value.substring(sep + 1);
        return effectivePlan(plan, end.isEmpty() ? null : LocalDateTime.parse(end));
    }

    // ============================================================
    // LINK EXPIRY
    // ============================================================

    /** When a link created at createdDate expires under plan; null if never. */
    public LocalDateTime linkExpiresAt(LocalDateTime createdDate, Plan plan) {
        Duration lifetime = plan.linkLifetime();
        if (lifetime == null || createdDate == null) {
            return null;
        }
        return createdDate.plus(lifetime);
    }

    public boolean isLinkActive(LocalDateTime createdDate, Plan plan) {
        LocalDateTime expiresAt = linkExpiresAt(createdDate, plan);
        return expiresAt == null || now().isBefore(expiresAt);
    }

    /**
     * Links created before this moment are expired under plan; null when the
     * plan's links never expire.
     */
    public LocalDateTime activeLinksCreatedAfter(Plan plan) {
        Duration lifetime = plan.linkLifetime();
        return lifetime == null ? null : now().minus(lifetime);
    }

    // ============================================================
    // SUBSCRIPTION CHANGES
    // ============================================================

    /**
     * Sets a user's subscription. Payments will call this later; for now only
     * the development endpoint does.
     */
    @Transactional
    public User changeSubscription(User user, Plan plan, LocalDateTime periodEnd) {
        if (plan == Plan.GUEST) {
            throw new IllegalArgumentException("GUEST is not a subscription plan");
        }
        if (plan.isPaid() && periodEnd == null) {
            throw new IllegalArgumentException("A paid plan needs a period end");
        }
        user.setPlan(plan);
        user.setPlanPeriodEnd(plan.isPaid() ? periodEnd : null);
        User saved = userRepository.save(user);
        try {
            redisTemplate.delete(OWNER_CACHE_PREFIX + user.getId());
        } catch (Exception e) {
            log.warn("Could not evict cached plan for user {}; it expires within {}",
                    user.getId(), OWNER_CACHE_TTL);
        }
        return saved;
    }
}
