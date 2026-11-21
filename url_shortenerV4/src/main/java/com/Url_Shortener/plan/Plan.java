package com.Url_Shortener.plan;

import java.time.Duration;

/**
 * What each kind of user may do. All per-plan rules live here so they can
 * be changed in one place.
 *
 * <ul>
 *   <li>linkLimit / limitPeriod: ACTIVE means at most this many working
 *       links at once (for GUEST, per browser; see GuestQuotaService).
 *       MONTH / YEAR mean at most this many links created per paid period;
 *       deleting a link does not give it back.</li>
 *   <li>linkLifetime: how long a link works and is shown after it is
 *       created; null means for as long as the plan is paid.</li>
 *   <li>editWindow: how long after creation the destination can be changed;
 *       null means any time. Deleting is always allowed.</li>
 *   <li>clickDetailRetention: how long individual clicks are kept. Daily
 *       totals are kept for as long as the link exists.</li>
 *   <li>analytics: click analytics are shown. Plans without analytics
 *       redirect with 301, which browsers cache, so repeat clicks mostly
 *       never reach the server; paid plans redirect with 302 so every click
 *       is counted.</li>
 *   <li>customCodes: the user may choose the short code.</li>
 * </ul>
 */
public enum Plan {

    GUEST(1, LimitPeriod.ACTIVE, Duration.ofDays(5), null, Duration.ofDays(30), false, false),
    FREE(50, LimitPeriod.ACTIVE, Duration.ofDays(5), Duration.ofHours(3), Duration.ofDays(30), false, false),
    MONTHLY(150, LimitPeriod.MONTH, null, null, Duration.ofDays(90), true, false),
    YEARLY(2000, LimitPeriod.YEAR, null, null, Duration.ofDays(3 * 365), true, true);

    /** What linkLimit counts. */
    public enum LimitPeriod { ACTIVE, MONTH, YEAR }

    private final int linkLimit;
    private final LimitPeriod limitPeriod;
    private final Duration linkLifetime;
    private final Duration editWindow;
    private final Duration clickDetailRetention;
    private final boolean analytics;
    private final boolean customCodes;

    Plan(int linkLimit, LimitPeriod limitPeriod, Duration linkLifetime, Duration editWindow,
         Duration clickDetailRetention, boolean analytics, boolean customCodes) {
        this.linkLimit = linkLimit;
        this.limitPeriod = limitPeriod;
        this.linkLifetime = linkLifetime;
        this.editWindow = editWindow;
        this.clickDetailRetention = clickDetailRetention;
        this.analytics = analytics;
        this.customCodes = customCodes;
    }

    public int linkLimit() {
        return linkLimit;
    }

    public LimitPeriod limitPeriod() {
        return limitPeriod;
    }

    /** Null when links last as long as the plan is paid. */
    public Duration linkLifetime() {
        return linkLifetime;
    }

    /** Null when links can be edited any time. */
    public Duration editWindow() {
        return editWindow;
    }

    public Duration clickDetailRetention() {
        return clickDetailRetention;
    }

    public boolean analytics() {
        return analytics;
    }

    public boolean customCodes() {
        return customCodes;
    }

    public boolean permanentRedirect() {
        return !analytics;
    }

    public boolean isPaid() {
        return this == MONTHLY || this == YEARLY;
    }

    /** Human-readable name for messages. */
    public String displayName() {
        return switch (this) {
            case GUEST -> "Guest";
            case FREE -> "Free";
            case MONTHLY -> "Monthly";
            case YEARLY -> "Yearly";
        };
    }
}
