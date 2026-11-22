package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RefreshTokenRequest;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.dto.UserProfileDto;
import com.Url_Shortener.oauth.OAuthLoginCodes;
import com.Url_Shortener.oauth.OAuthProviders;
import com.Url_Shortener.security.jwt.JwtAuthenticationResponse;
import com.Url_Shortener.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
     private final UserService userService;
     private final OAuthProviders oauthProviders;
     private final OAuthLoginCodes oauthCodes;

     public AuthController(UserService userService, OAuthProviders oauthProviders, OAuthLoginCodes oauthCodes) {
         this.userService = userService;
         this.oauthProviders = oauthProviders;
         this.oauthCodes = oauthCodes;
     }

     /** Sign-in providers that are set up, e.g. ["google", "github"], for the login buttons. */
     @GetMapping("/public/oauth/providers")
     public ResponseEntity<ApiResponse<List<String>>> oauthProviders() {
         return ResponseEntity.ok(ApiResponse.success(oauthProviders.enabled()));
     }

     /** Request body for the code exchange. */
     public record OAuthExchangeRequest(String code) {
     }

     /**
      * Exchanges the one-time code from a Google/GitHub sign-in for the usual
      * access and refresh tokens. Each code works once, within 60 seconds.
      */
     @PostMapping("/public/oauth/exchange")
     public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> exchangeOAuthCode(
             @RequestBody OAuthExchangeRequest request) {
         Long userId = oauthCodes.consume(request.code());
         return ResponseEntity.ok(ApiResponse.success(userService.issueTokens(userId)));
     }

     @PostMapping("/public/login") //✅
     public ResponseEntity<ApiResponse<?>> loginUser(@Valid @RequestBody LoginRequestDto loginRequest){
         return ResponseEntity.ok(ApiResponse.success(userService.authenticateUser(loginRequest)));
     }

     @PostMapping("/public/register") //✅
     public ResponseEntity<ApiResponse<?>> registerUser(@Valid @RequestBody RegisterRequestDto registerRequest){
         userService.registerUser(registerRequest);
         return ResponseEntity.ok(ApiResponse.success(null, "User registered successfully"));
     }

     @GetMapping("/me")  //✅
     @PreAuthorize("hasRole('USER')")
     public ResponseEntity<ApiResponse<UserProfileDto>> getCurrentUser(Principal principal){
         UserProfileDto profile = userService.getUserProfile(principal.getName());
         return ResponseEntity.ok(ApiResponse.success(profile));
     }

     @PostMapping("/public/refresh") //✅
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
