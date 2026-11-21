package com.Url_Shortener.security;

import com.Url_Shortener.shortcode.ShortCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-IP, fixed-window rate limiting backed by Redis, so limits are shared
 * across app instances. If Redis is unavailable, requests are allowed.
 *
 * Uses the connection's remote address: behind a reverse proxy, configure
 * forwarded headers so this is the client rather than the proxy.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final long authLimit;
    private final long shortenLimit;
    private final long redirectLimit;

    public RateLimitFilter(StringRedisTemplate redisTemplate,
                           @Value("${app.rate-limit.auth-per-minute:20}") long authLimit,
                           @Value("${app.rate-limit.shorten-per-minute:60}") long shortenLimit,
                           @Value("${app.rate-limit.redirect-per-minute:600}") long redirectLimit) {
        this.redisTemplate = redisTemplate;
        this.authLimit = authLimit;
        this.shortenLimit = shortenLimit;
        this.redirectLimit = redirectLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v1/auth/public/")) {
            if (isOverLimit("auth", request, authLimit)) {
                reject(response);
                return;
            }
        } else if ("POST".equals(method) && path.equals("/api/v1/urls/shorten")) {
            if (isOverLimit("shorten", request, shortenLimit)) {
                reject(response);
                return;
            }
        } else if ("GET".equals(method) && ShortCode.isShortCodePath(path)) {
            if (isOverLimit("redirect", request, redirectLimit)) {
                reject(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isOverLimit(String scope, HttpServletRequest request, long limit) {
        long window = Instant.now().getEpochSecond() / WINDOW.toSeconds();
        String key = "rate:" + scope + ":" + request.getRemoteAddr() + ":" + window;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, WINDOW.multipliedBy(2));
            }
            return count != null && count > limit;
        } catch (Exception e) {
            log.warn("Rate limit check failed, allowing request: {}", e.getMessage());
            return false;
        }
    }

    private void reject(HttpServletResponse response) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()));
    }
}
