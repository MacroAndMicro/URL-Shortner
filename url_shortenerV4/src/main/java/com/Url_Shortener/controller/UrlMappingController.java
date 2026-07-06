package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.dto.CreateShortUrlRequest;
import com.Url_Shortener.dto.DashboardDto;
import com.Url_Shortener.dto.UpdateUrlMappingRequest;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.service.UrlMappingService;
import com.Url_Shortener.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping ("/api/v1/urls")
public class UrlMappingController {
    private final UrlMappingService urlMappingService;
    private final UserService userService;

    public UrlMappingController(UrlMappingService urlMappingService, UserService userService) {
        this.urlMappingService = urlMappingService;
        this.userService = userService;
    }

//    {"originalUrl":"https://example.com"}
//    https://abc.com/QN7XOa0a --> https://example.com

    @PostMapping("/shorten")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<UrlMappingDto>> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request,
                                                                      Principal principal){
        User user = userService.findByUsername(principal.getName());
        UrlMappingDto urlMappingDTO = urlMappingService.createShortUrl(request.getOriginalUrl(), user);
        return ResponseEntity.ok(ApiResponse.success(urlMappingDTO, "Short URL created successfully"));
    }


    @GetMapping("/myurls")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<Page<UrlMappingDto>>> getUserUrls(
            Principal principal,
            @PageableDefault(size = 20, sort = "createdDate") Pageable pageable){
        User user = userService.findByUsername(principal.getName());
        Page<UrlMappingDto> urls = urlMappingService.getUrlsByUser(user, pageable);
        return ResponseEntity.ok(ApiResponse.success(urls));
    }


    @GetMapping("/analytics/{shortUrl}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<List<ClickEventDto>>> getUrlAnalytics(@PathVariable String shortUrl,
                                                                             @RequestParam("startDate") String startDate,
                                                                             @RequestParam("endDate") String endDate,
                                                                             Principal principal){
        User user = userService.findByUsername(principal.getName());
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime start = LocalDateTime.parse(startDate, formatter);
        LocalDateTime end = LocalDateTime.parse(endDate, formatter);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must be before endDate");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new IllegalArgumentException("Date range must not exceed 366 days");
        }
        List<ClickEventDto> clickEventDTOS = urlMappingService.getClickEventsByDate(shortUrl, start, end, user);
        return ResponseEntity.ok(ApiResponse.success(clickEventDTOS));
    }


    @GetMapping("/totalClicks")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<List<ClickEventDto>>> getTotalClicksByDate(Principal principal,
                                                                                  @RequestParam("startDate") String startDate,
                                                                                  @RequestParam("endDate") String endDate){
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        User user = userService.findByUsername(principal.getName());
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must be before endDate");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new IllegalArgumentException("Date range must not exceed 366 days");
        }
        List<ClickEventDto> totalClicks = urlMappingService.getTotalClicksByUserAndDate(user, start, end);
        return ResponseEntity.ok(ApiResponse.success(totalClicks));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<UrlMappingDto>> getUrlById(@PathVariable Long id,
                                                                  Principal principal){
        User user = userService.findByUsername(principal.getName());
        UrlMappingDto urlMapping = urlMappingService.getUrlByIdAndUser(id, user);
        return ResponseEntity.ok(ApiResponse.success(urlMapping));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<UrlMappingDto>> updateUrl(@PathVariable Long id,
                                                                 @Valid @RequestBody UpdateUrlMappingRequest request,
                                                                 Principal principal){
        User user = userService.findByUsername(principal.getName());
        UrlMappingDto updated = urlMappingService.updateUrl(id, request.getOriginalUrl(), user);
        return ResponseEntity.ok(ApiResponse.success(updated, "URL updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<Void>> deleteUrl(@PathVariable Long id,
                                                        Principal principal){
        User user = userService.findByUsername(principal.getName());
        urlMappingService.deleteUrl(id, user);
        return ResponseEntity.ok(ApiResponse.success(null, "URL deleted successfully"));
    }

    @GetMapping("/analytics/{shortUrl}/details")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<List<ClickEventDetailDto>>> getUrlAnalyticsDetails(
            @PathVariable String shortUrl,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            Principal principal){
        User user = userService.findByUsername(principal.getName());
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime start = LocalDateTime.parse(startDate, formatter);
        LocalDateTime end = LocalDateTime.parse(endDate, formatter);
        List<ClickEventDetailDto> details = urlMappingService.getClickEventDetails(shortUrl, start, end, user);
        return ResponseEntity.ok(ApiResponse.success(details));
    }

    @GetMapping("/top")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<List<UrlMappingDto>>> getTopUrls(
            Principal principal,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate){
        User user = userService.findByUsername(principal.getName());
        List<UrlMappingDto> topUrls;
        if (startDate != null && endDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end = LocalDate.parse(endDate, formatter);
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("startDate must be before endDate");
            }
            topUrls = urlMappingService.getTopUrlsByDate(user, limit, start, end);
        } else {
            topUrls = urlMappingService.getTopUrls(user, limit);
        }
        return ResponseEntity.ok(ApiResponse.success(topUrls));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<DashboardDto>> getDashboard(Principal principal){
        User user = userService.findByUsername(principal.getName());
        DashboardDto dashboard = urlMappingService.getDashboard(user);
        return ResponseEntity.ok(ApiResponse.success(dashboard));
    }
}
