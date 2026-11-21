package com.Url_Shortener.plan;

import java.time.LocalDateTime;

/**
 * A link owner's subscription as it stands now.
 *
 * @param effective  the plan whose rules apply now
 * @param subscribed the plan the owner subscribed to
 * @param periodEnd  end of the paid period; null for FREE and GUEST
 */
public record OwnerStatus(Plan effective, Plan subscribed, LocalDateTime periodEnd) {

    /** Links without an owner. */
    public static final OwnerStatus GUEST = new OwnerStatus(Plan.GUEST, Plan.GUEST, null);

    /**
     * When the paid plan stopped applying (the end of the grace period), if
     * this owner is FREE because a paid plan lapsed; otherwise null.
     */
    public LocalDateTime lapsedAt() {
        if (subscribed.isPaid() && !effective.isPaid() && periodEnd != null) {
            return periodEnd.plus(PlanService.GRACE_PERIOD);
        }
        return null;
    }
}
