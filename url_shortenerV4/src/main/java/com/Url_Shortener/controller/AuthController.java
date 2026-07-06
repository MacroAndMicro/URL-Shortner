package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RefreshTokenRequest;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.dto.UserProfileDto;
import com.Url_Shortener.security.jwt.JwtAuthenticationResponse;
import com.Url_Shortener.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
     private final UserService userService;

     public AuthController(UserService userService) {
         this.userService = userService;
     }

     @PostMapping("/public/login")
     public ResponseEntity<ApiResponse<?>> loginUser(@Valid @RequestBody LoginRequestDto loginRequest){
         return ResponseEntity.ok(ApiResponse.success(userService.authenticateUser(loginRequest)));
     }

     @PostMapping("/public/register")
     public ResponseEntity<ApiResponse<?>> registerUser(@Valid @RequestBody RegisterRequestDto registerRequest){
         userService.registerUser(registerRequest);
         return ResponseEntity.ok(ApiResponse.success(null, "User registered successfully"));
     }

     @GetMapping("/me")
     @PreAuthorize("hasRole('USER')")
     public ResponseEntity<ApiResponse<UserProfileDto>> getCurrentUser(Principal principal){
         UserProfileDto profile = userService.getUserProfile(principal.getName());
         return ResponseEntity.ok(ApiResponse.success(profile));
     }

     @PostMapping("/public/refresh")
     public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> refreshToken(
             @Valid @RequestBody RefreshTokenRequest request){
         return ResponseEntity.ok(ApiResponse.success(userService.refreshToken(request.getRefreshToken())));
     }

     @PostMapping("/logout")
     @PreAuthorize("hasRole('USER')")
     public ResponseEntity<ApiResponse<Void>> logout(Principal principal){
         userService.logout(principal.getName());
         return ResponseEntity.ok(ApiResponse.success(null, "Logged out successfully"));
     }
}
