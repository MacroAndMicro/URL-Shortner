package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/v1/health") //✅
    public ResponseEntity<ApiResponse<Void>> health() {
        return ResponseEntity.ok(ApiResponse.success(null, "Service is up"));
    }
}
