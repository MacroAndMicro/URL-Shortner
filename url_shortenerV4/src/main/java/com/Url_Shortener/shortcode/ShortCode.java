package com.Url_Shortener.shortcode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a short code may look like.
 *
 * REGEX is what the redirect route, the security rule and the rate limiter
 * accept, so they cannot drift. Generated codes are 10-11 base62 characters
 * (older random ones are 8); custom codes follow the stricter rules below.
 */
public final class ShortCode {

    public static final String REGEX = "[A-Za-z0-9_-]{3,32}";

    /** Spring path pattern for the redirect route, e.g. GET /aB3xY9kQ2m. */
    public static final String PATH_PATTERN = "/{shortUrl:" + REGEX + "}";

    private static final Pattern PATH = Pattern.compile("/" + REGEX);

    // ============================================================
    // CUSTOM CODES
    // ============================================================

    public static final int CUSTOM_MIN_LENGTH = 4;
    public static final int CUSTOM_MAX_LENGTH = 30;

    /** Letters, digits and hyphens; starts and ends with a letter or digit. */
    private static final Pattern CUSTOM = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?");

    /** Words that are, or may become, pages or API paths of the site. */
    static final Set<String> RESERVED = Set.of(
            "about", "account", "actuator", "admin", "analytics", "api", "app", "assets",
            "billing", "dashboard", "error", "favicon", "guest", "health", "help", "home",
            "login", "logout", "plan", "plans", "pricing", "register", "settings",
            "signin", "signup", "static", "support", "terms", "privacy", "urls", "www");

    private ShortCode() {
    }

    public static boolean isShortCodePath(String path) {
        return path != null && PATH.matcher(path).matches();
    }

    /**
     * Checks a custom code and returns the form it is stored under. Custom
     * codes are not case sensitive: MyLink and mylink are the same code,
     * stored in lower case, and the first to claim it gets it.
     *
     * @throws IllegalArgumentException with a message for the user
     */
    public static String normalizeCustom(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.length() < CUSTOM_MIN_LENGTH || trimmed.length() > CUSTOM_MAX_LENGTH) {
            throw new IllegalArgumentException("Custom URLs must be " + CUSTOM_MIN_LENGTH + " to "
                    + CUSTOM_MAX_LENGTH + " characters long.");
        }
        if (!CUSTOM.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Custom URLs can use letters, digits and hyphens, "
                    + "and must start and end with a letter or digit.");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(normalized)) {
            throw new IllegalArgumentException("'" + normalized + "' is reserved. Please choose another.");
        }
        return normalized;
    }
}
