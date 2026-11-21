package com.Url_Shortener.controller;

import com.Url_Shortener.service.RedirectTarget;
import com.Url_Shortener.service.UrlMappingService;
import com.Url_Shortener.shortcode.ShortCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Sends visitors from a short link to its destination.
 *
 * Paid plans get 302: browsers ask the server on every click, so every click
 * is counted. Guest and Free links get 301, which browsers cache, so repeat
 * clicks skip the server entirely; the cache lifetime is the time left until
 * the link expires, so browsers stop using it when the link does.
 */
@RestController
public class RedirectController {

    private final UrlMappingService urlMappingService;

    public RedirectController(UrlMappingService urlMappingService) {
        this.urlMappingService = urlMappingService;
    }

    @GetMapping(ShortCode.PATH_PATTERN)
    public ResponseEntity<Void> redirect(@PathVariable("shortUrl") String shortUrl,
                                          HttpServletRequest request) {
        return urlMappingService.resolve(
                        shortUrl,
                        request.getRemoteAddr(),
                        request.getHeader("Referer"),
                        request.getHeader("User-Agent"))
                .map(RedirectController::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Void> toResponse(RedirectTarget target) {
        if (!target.permanent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, target.originalUrl())
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        CacheControl cache = target.maxAgeSeconds() != null
                ? CacheControl.maxAge(Duration.ofSeconds(target.maxAgeSeconds())).cachePublic()
                : CacheControl.empty();
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, target.originalUrl())
                .cacheControl(cache)
                .build();
    }
}
