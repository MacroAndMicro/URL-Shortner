package com.Url_Shortener.dto;

import com.Url_Shortener.plan.Plan;

import java.time.LocalDateTime;

/**
 * A user's plan and usage, for the frontend.
 *
 * @param subscribedPlan       the plan the user subscribed to
 * @param effectivePlan        the plan whose rules apply now (FREE once a
 *                             paid plan's grace period has passed)
 * @param periodEnd            end of the paid period; null for FREE
 * @param inGracePeriod        paid period ended, grace period not yet
 * @param graceEndsAt          when the user falls back to FREE; null for FREE
 * @param maxActiveUrls        link limit of the effective plan
 * @param activeUrls           links that have not expired
 * @param linkLifetimeDays     how long links last; null if forever
 * @param analytics            whether click analytics are available
 * @param redirectStatus       301 or 302
 * @param devPlanSwitchEnabled whether the development plan switch is on
 */
public record PlanInfoDto(
        Plan subscribedPlan,
        Plan effectivePlan,
        LocalDateTime periodEnd,
        boolean inGracePeriod,
        LocalDateTime graceEndsAt,
        int maxActiveUrls,
        long activeUrls,
        Long linkLifetimeDays,
        boolean analytics,
        int redirectStatus,
        boolean devPlanSwitchEnabled) {
}
