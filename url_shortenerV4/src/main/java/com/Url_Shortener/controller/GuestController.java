package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.dto.CreateShortUrlRequest;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.service.UrlMappingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shortening for visitors without an account. The frontend identifies the
 * browser with a random UUID it keeps in local storage (X-Guest-Id).
 */
@RestController
@RequestMapping("/api/v1/guest")
public class GuestController {

    public static final String GUEST_ID_HEADER = "X-Guest-Id";

    private final UrlMappingService urlMappingService;

    public GuestController(UrlMappingService urlMappingService) {
        this.urlMappingService = urlMappingService;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ApiResponse<UrlMappingDto>> shorten(
            @Valid @RequestBody CreateShortUrlRequest request,
            @RequestHeader(value = GUEST_ID_HEADER, required = false) String guestId,
            HttpServletRequest httpRequest) {
        UrlMappingDto dto = urlMappingService.createGuestShortUrl(
                request.getOriginalUrl(), request.getCustomCode(), guestId, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success(dto, "Short URL created successfully"));
    }
}
