package com.Url_Shortener.controller;

import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.service.UserService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthController {
     private UserService userService;

     @PostMapping("/public/login")
     public ResponseEntity<?> loginUser(@RequestBody LoginRequestDto loginRequest){
         return ResponseEntity.ok(userService.authenticateUser(loginRequest));
     }

     @PostMapping("/public/register")
     public ResponseEntity<?> registerUser(@RequestBody RegisterRequestDto registerRequest){
         userService.registerUser(registerRequest);
         return ResponseEntity.ok("User registered successfully");
     }

}
