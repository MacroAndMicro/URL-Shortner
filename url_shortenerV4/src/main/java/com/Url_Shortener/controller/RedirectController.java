package com.Url_Shortener.controller;

import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.service.UrlMappingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("/api/v1/redirect")
public class RedirectController {

    private final UrlMappingService urlMappingService;

    public RedirectController(UrlMappingService urlMappingService) {
        this.urlMappingService = urlMappingService;
    }

    @GetMapping("/shortUrl") //✅
    public ResponseEntity<Void> redirect(@RequestParam("shortUrl") String shortUrl,
                                          HttpServletRequest request){
        UrlMapping urlMapping = urlMappingService.getOriginalUrl(
                shortUrl,
                request.getRemoteAddr(),
                request.getHeader("Referer"),
                request.getHeader("User-Agent")
        );
        if (urlMapping != null) {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.add("Location", urlMapping.getOriginalUrl());
            return ResponseEntity.status(302).headers(httpHeaders).build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
