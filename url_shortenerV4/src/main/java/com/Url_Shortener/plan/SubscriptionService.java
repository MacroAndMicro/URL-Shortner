package com.Url_Shortener.plan;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Changes a user's plan and applies the change to their links.
 *
 * <ul>
 *   <li>Links that have already expired stay expired for good: their expiry
 *       is stored, so upgrading does not bring them back.</li>
 *   <li>Upgrading to a paid plan makes the user's working links permanent.</li>
 *   <li>Moving to FREE starts the 5-day countdown for links made while paid.</li>
 * </ul>
 *
 * A paid plan lapsing on its own (no payment) is not a change made here; its
 * effect on links is computed by PlanService.linkExpiresAt.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PlanService planService;
    private final UserRepository userRepository;
    private final UrlMappingDynamoRepository urlMappingRepository;
    private final CacheService cacheService;

    public SubscriptionService(PlanService planService, UserRepository userRepository,
                               UrlMappingDynamoRepository urlMappingRepository, CacheService cacheService) {
        this.planService = planService;
        this.userRepository = userRepository;
        this.urlMappingRepository = urlMappingRepository;
        this.cacheService = cacheService;
    }

    /**
     * Sets a user's subscription. Payments will call this later; for now only
     * the development endpoint does.
     */
    public User changeSubscription(User user, Plan plan, LocalDateTime periodEnd) {
        if (plan == Plan.GUEST) {
            throw new IllegalArgumentException("GUEST is not a subscription plan");
        }
        if (plan.isPaid() && periodEnd == null) {
            throw new IllegalArgumentException("A paid plan needs a period end");
        }

        OwnerStatus before = planService.status(user);
        user.setPlan(plan);
        user.setPlanPeriodEnd(plan.isPaid() ? periodEnd : null);
        User saved = userRepository.save(user);
        OwnerStatus after = planService.status(saved);
        planService.evictOwner(user.getId());

        int changed = 0;
        for (UrlMappingItem item : urlMappingRepository.findByOwnerId(user.getId())) {
            String target = storedExpiryAfterChange(item, before, after);
            if (!Objects.equals(target, item.getExpiresAt())) {
                urlMappingRepository.setExpiresAt(item.getShortUrl(), target);
                cacheService.evict(item.getShortUrl());
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Plan change for user {} ({} -> {}) updated the expiry of {} links",
                    user.getId(), before.effective(), after.effective(), changed);
        }
        return saved;
    }

    /** The expiry to store on a link once the owner's plan changes from before to after. */
    String storedExpiryAfterChange(UrlMappingItem item, OwnerStatus before, OwnerStatus after) {
        LocalDateTime now = planService.now();
        LocalDateTime stored = parse(item.getExpiresAt());
        LocalDateTime current = planService.linkExpiresAt(parse(item.getCreatedDate()), stored, before);

        if (current != null && !now.isBefore(current)) {
            return format(current);                  // already expired: for good
        }
        if (after.effective().isPaid()) {
            return null;                             // upgraded: no longer expires
        }
        if (stored != null) {
            return item.getExpiresAt();              // already counting down
        }
        if (after.lapsedAt() != null) {
            return null;                             // lapsed plan: computed from the lapse
        }
        // Moved to FREE: computed expiry if it had one, else the 5-day countdown starts now.
        return format(current != null ? current : planService.newLinkExpiresAt(after.effective()));
    }

    private static LocalDateTime parse(String isoDateTime) {
        return isoDateTime != null ? LocalDateTime.parse(isoDateTime, ISO) : null;
    }

    private static String format(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(ISO) : null;
    }
}
