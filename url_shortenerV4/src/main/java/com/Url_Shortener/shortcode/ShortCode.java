package com.Url_Shortener.shortcode;

import java.util.regex.Pattern;

/**
 * The one definition of what a short code may look like. The redirect route,
 * the security rule and the rate limiter all use it, so they cannot drift.
 *
 * Generated codes are 10-11 base62 characters. Older random codes are 8.
 * The wider range and '-' / '_' leave room for custom codes.
 */
public final class ShortCode {

    public static final String REGEX = "[A-Za-z0-9_-]{3,32}";

    /** Spring path pattern for the redirect route, e.g. GET /aB3xY9kQ2m. */
    public static final String PATH_PATTERN = "/{shortUrl:" + REGEX + "}";

    private static final Pattern PATH = Pattern.compile("/" + REGEX);

    private ShortCode() {
    }

    public static boolean isShortCodePath(String path) {
        return path != null && PATH.matcher(path).matches();
    }
}
