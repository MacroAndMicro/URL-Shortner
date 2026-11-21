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
 * @param paidLinksExpireAt    if a paid plan lapsed, when the links made
 *                             while paid stop working for good; else null
 * @param linkLimit            the effective plan's link limit
 * @param limitPeriod          what linkLimit counts: ACTIVE links at once, or
 *                             links created per MONTH / YEAR
 * @param linksUsed            links counted against linkLimit
 * @param linkLifetimeDays     how long new links last; null while paid
 * @param editWindowHours      how long after creation links can be changed;
 *                             null if any time
 * @param customCodes          whether the user can choose short codes
 * @param analytics            whether click analytics are shown
 * @param clickDetailRetentionDays how long individual clicks are kept
 * @param redirectStatus       301 or 302
 * @param devPlanSwitchEnabled whether the development plan switch is on
 */
public record PlanInfoDto(
        Plan subscribedPlan,
        Plan effectivePlan,
        LocalDateTime periodEnd,
        boolean inGracePeriod,
        LocalDateTime graceEndsAt,
        LocalDateTime paidLinksExpireAt,
        int linkLimit,
        Plan.LimitPeriod limitPeriod,
        long linksUsed,
        Long linkLifetimeDays,
        Long editWindowHours,
        boolean customCodes,
        boolean analytics,
        long clickDetailRetentionDays,
        int redirectStatus,
        boolean devPlanSwitchEnabled) {
}
