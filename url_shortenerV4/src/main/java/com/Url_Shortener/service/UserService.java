package com.Url_Shortener.service;

import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.jwt.JwtAuthenticationResponse;
import com.Url_Shortener.security.jwt.JwtUtils;
import lombok.AllArgsConstructor;
//import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class UserService {
    private PasswordEncoder passwordEncoder;
    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private JwtUtils jwtUtils;

    public JwtAuthenticationResponse authenticateUser(LoginRequestDto loginRequest){
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(),
                        loginRequest.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String jwt = jwtUtils.generateToken(userDetails);
        return new JwtAuthenticationResponse(jwt);
    }


    public User registerUser(RegisterRequestDto registerRequest){
        User user = new User();
        if(userRepository.existsByUsername(registerRequest.getUsername()))
        {
            throw new RuntimeException("Username already exists");
        }

        if(userRepository.existsByEmail(registerRequest.getEmail()))
        {
            throw new RuntimeException("Email already exists");
        }
        user.setUsername(registerRequest.getUsername());

        user.setEmail(registerRequest.getEmail());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setRole("ROLE_USER");
        return userRepository.save(user);
    }



    public User findByUsername(String name) {
        return userRepository.findByUsername(name).orElseThrow(
                () -> new UsernameNotFoundException("User not found with username: " + name)
        );
    }
}
