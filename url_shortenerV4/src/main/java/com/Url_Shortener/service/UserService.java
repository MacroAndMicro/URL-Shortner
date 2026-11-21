package com.Url_Shortener.service;

import com.Url_Shortener.dto.LoginRequestDto;
import com.Url_Shortener.dto.RegisterRequestDto;
import com.Url_Shortener.dto.UserProfileDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.exception.ConflictException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.jwt.JwtAuthenticationResponse;
import com.Url_Shortener.security.jwt.JwtUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
    private final UrlShortenerMetrics metrics;

    public UserService(PasswordEncoder passwordEncoder, UserRepository userRepository,
                       AuthenticationManager authenticationManager, JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService, UrlShortenerMetrics metrics) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
        this.metrics = metrics;
    }

    public JwtAuthenticationResponse authenticateUser(LoginRequestDto loginRequest){
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getUsername(),
                            loginRequest.getPassword()));
        } catch (AuthenticationException e) {
            metrics.incrementAuthLoginFailure();
            throw e;
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String jwt = jwtUtils.generateToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(userDetails.getUsername()).token();
        metrics.incrementAuthLoginSuccess();
        return new JwtAuthenticationResponse(jwt, refreshToken);
    }


    public User registerUser(RegisterRequestDto registerRequest){
        User user = new User();
        if(userRepository.existsByUsername(registerRequest.getUsername()))
        {
            throw new ConflictException("Username already exists");
        }

        if(userRepository.existsByEmail(registerRequest.getEmail()))
        {
            throw new ConflictException("Email already exists");
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
        metrics.incrementAuthRefresh();
        RefreshTokenService.IssuedRefreshToken newRefreshToken = refreshTokenService.verifyAndRotate(refreshTokenValue);
        UserDetailsImpl userDetails = UserDetailsImpl.build(newRefreshToken.user());
        String jwt = jwtUtils.generateToken(userDetails);
        return new JwtAuthenticationResponse(jwt, newRefreshToken.token());
    }

    public void logout(String username) {
        refreshTokenService.deleteByUsername(username);
    }
}
