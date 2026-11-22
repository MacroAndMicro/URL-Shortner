package com.Url_Shortener.oauth;

import com.Url_Shortener.exception.InvalidSignInCodeException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.resilience.Breakers;
import com.Url_Shortener.resilience.Guarded;
import com.Url_Shortener.security.Sha256;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * One-time codes that hand a finished Google/GitHub sign-in to the frontend.
 *
 * After the provider sends the user back, the backend redirects the browser
 * to the frontend with a code rather than with tokens, so tokens never appear
 * in a URL, browser history or server logs. The frontend exchanges the code
 * for tokens once; it works for CODE_TTL and only once.
 *
 * Only a hash of the code is stored in Redis.
 */
@Service
public class OAuthLoginCodes {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginCodes.class);

    static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "oauth:code:";

    private final StringRedisTemplate redisTemplate;
    private final Guarded redis;
    private final SecureRandom random = new SecureRandom();

    public OAuthLoginCodes(StringRedisTemplate redisTemplate, CircuitBreakerRegistry breakers) {
        this.redisTemplate = redisTemplate;
        this.redis = new Guarded(breakers.circuitBreaker(Breakers.REDIS), log);
    }

    /** A new code for this user. */
    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.run(() -> redisTemplate.opsForValue().set(key(code), userId.toString(), CODE_TTL));
        } catch (RuntimeException e) {
            redis.logFailure("Storing sign-in code", e);
            throw new ServiceUnavailableException("Sign-in is temporarily unavailable. Please try again shortly.");
        }
        return code;
    }

    /**
     * The user the code was issued for. The code is deleted in the same step,
     * so it cannot be used twice.
     *
     * @throws InvalidSignInCodeException (401) if unknown, used or expired
     */
    public Long consume(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidSignInCodeException("Sign-in code is missing");
        }
        String userId;
        try {
            userId = redis.call(() -> redisTemplate.opsForValue().getAndDelete(key(code)));
        } catch (RuntimeException e) {
            redis.logFailure("Reading sign-in code", e);
            throw new ServiceUnavailableException("Sign-in is temporarily unavailable. Please try again shortly.");
        }
        if (userId == null) {
            throw new InvalidSignInCodeException("Sign-in code is invalid or has expired. Please sign in again.");
        }
        return Long.valueOf(userId);
    }

    private static String key(String code) {
        return PREFIX + Sha256.hex(code);
    }
}
