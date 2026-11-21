package com.Url_Shortener.service;

/**
 * Where a short link sends the visitor, and how.
 *
 * @param originalUrl   the destination
 * @param permanent     true for 301 (browsers cache it, so later clicks are
 *                      not seen by the server), false for 302
 * @param maxAgeSeconds for a 301, how long browsers may cache it: the time
 *                      left until the link expires, so they stop using it
 *                      when it does; null when the link never expires
 */
public record RedirectTarget(String originalUrl, boolean permanent, Long maxAgeSeconds) {
}
