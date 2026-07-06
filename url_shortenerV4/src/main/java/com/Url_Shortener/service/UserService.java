package com.Url_Shortener.service;

import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.dto.UserProfileDto;
import com.Url_Shortener.entity.RefreshToken;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.jwt.JwtAuthenticationResponse;
import com.Url_Shortener.security.jwt.JwtUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class UserService {
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    public UserService(PasswordEncoder passwordEncoder, UserRepository userRepository,
                       AuthenticationManager authenticationManager, JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
    }

    public JwtAuthenticationResponse authenticateUser(LoginRequestDto loginRequest){
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(),
                        loginRequest.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String jwt = jwtUtils.generateToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(userDetails.getUsername()).getToken();
        return new JwtAuthenticationResponse(jwt, refreshToken);
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

        user.setRoles(Set.of("ROLE_USER"));
        return userRepository.save(user);
    }



    public User findByUsername(String name) {
        return userRepository.findByUsername(name).orElseThrow(
                () -> new UsernameNotFoundException("User not found with username: " + name)
        );
    }

    public UserProfileDto getUserProfile(String username) {
        User user = findByUsername(username);
        return new UserProfileDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRoles(),
                user.getCreatedDate()
        );
    }

    public JwtAuthenticationResponse refreshToken(String refreshTokenValue) {
        RefreshToken newRefreshToken = refreshTokenService.verifyAndRotate(refreshTokenValue);
        User user = newRefreshToken.getUser();
        UserDetailsImpl userDetails = UserDetailsImpl.build(user);
        String jwt = jwtUtils.generateToken(userDetails);
        return new JwtAuthenticationResponse(jwt, newRefreshToken.getToken());
    }

    public void logout(String username) {
        refreshTokenService.deleteByUsername(username);
    }
}
