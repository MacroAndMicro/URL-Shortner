package com.url_shortenerV3.controller;


import com.url_shortenerV3.dto.LoginRequestDto;
import com.url_shortenerV3.dto.RegisterRequestDto;
import com.url_shortenerV3.entity.User;
import com.url_shortenerV3.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
//@AllArgsConstructor
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
