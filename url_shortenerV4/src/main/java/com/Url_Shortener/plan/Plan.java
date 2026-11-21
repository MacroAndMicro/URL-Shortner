package com.Url_Shortener.plan;

import java.time.Duration;

/**
 * What each kind of user may do. All per-plan rules live here so they can
 * be changed in one place.
 *
 * <ul>
 *   <li>maxActiveUrls: links that have not expired. For GUEST it applies per
 *       browser (see GuestQuotaService), with a separate per-IP daily cap.</li>
 *   <li>linkLifetime: how long a link works and stays in history after it is
 *       created; null means forever. Evaluated against the owner's current
 *       plan, so the rules of a lapsed subscription follow the user.</li>
 *   <li>analytics: click tracking and the analytics endpoints. Plans without
 *       analytics redirect with 301, which browsers cache, so later clicks
 *       never reach the server; paid plans redirect with 302 so every click
 *       is counted.</li>
 * </ul>
 */
public enum Plan {

    GUEST(1, Duration.ofDays(5), false),
    FREE(50, Duration.ofDays(5), false),
    MONTHLY(150, null, true),
    YEARLY(500, null, true);

    private final int maxActiveUrls;
    private final Duration linkLifetime;
    private final boolean analytics;

    Plan(int maxActiveUrls, Duration linkLifetime, boolean analytics) {
        this.maxActiveUrls = maxActiveUrls;
        this.linkLifetime = linkLifetime;
        this.analytics = analytics;
    }

    public int maxActiveUrls() {
        return maxActiveUrls;
    }

    /** Null when links never expire. */
    public Duration linkLifetime() {
        return linkLifetime;
    }

    public boolean analytics() {
        return analytics;
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
