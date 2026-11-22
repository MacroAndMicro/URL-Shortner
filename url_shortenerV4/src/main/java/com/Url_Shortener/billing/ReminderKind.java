package com.Url_Shortener.billing;

import com.Url_Shortener.plan.PlanService;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The payment reminders, each sent at most once per paid period, while
 * "now" is inside its window (so a missed day is caught up the next day).
 * Times are relative to the paid period's end ("due").
 */
public enum ReminderKind {

    /** 3 days before the due date. */
    DUE_SOON(Duration.ofDays(-3), Duration.ZERO),
    /** The due date. */
    DUE_TODAY(Duration.ZERO, Duration.ofDays(1)),
    /** 3 days into the 7-day grace period. */
    GRACE(Duration.ofDays(3), PlanService.GRACE_PERIOD.minusDays(1)),
    /** The last day of the grace period. */
    GRACE_LAST_DAY(PlanService.GRACE_PERIOD.minusDays(1), PlanService.GRACE_PERIOD),
    /** The plan has lapsed; links made on it stop 5 days later. */
    LAPSED(PlanService.GRACE_PERIOD, PlanService.GRACE_PERIOD.plusDays(4)),
    /** The day before those links stop for good. */
    LINKS_LAST_DAY(PlanService.GRACE_PERIOD.plusDays(4), PlanService.GRACE_PERIOD.plusDays(5));

    private final Duration from;
    private final Duration until;

    ReminderKind(Duration from, Duration until) {
        this.from = from;
        this.until = until;
    }

    /** The reminder whose window contains now, for a period ending at due; null if none. */
    public static ReminderKind at(LocalDateTime due, LocalDateTime now) {
        for (ReminderKind kind : values()) {
            if (!now.isBefore(due.plus(kind.from)) && now.isBefore(due.plus(kind.until))) {
                return kind;
            }
        }
        return null;
    }

    /** Earliest and latest period ends that can have a reminder due now. */
    public static LocalDateTime earliestDue(LocalDateTime now) {
        return now.minus(LINKS_LAST_DAY.until);
    }

    public static LocalDateTime latestDue(LocalDateTime now) {
        return now.minus(DUE_SOON.from);
    }
}
