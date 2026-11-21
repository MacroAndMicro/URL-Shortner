package com.Url_Shortener.service;

import com.Url_Shortener.entity.RefreshToken;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.exception.InvalidRefreshTokenException;
import com.Url_Shortener.repository.RefreshTokenRepository;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.Sha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Only a SHA-256 hash of each refresh token is stored, so a database
 * leak does not expose usable tokens. The raw value is returned once,
 * when the token is issued.
 */
@Service
public class RefreshTokenService {

    public record IssuedRefreshToken(String token, User user) {
    }

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpirationMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public IssuedRefreshToken createRefreshToken(String username) {
        User user = findUser(username);
        return issue(user);
    }

    @Transactional
    public IssuedRefreshToken verifyAndRotate(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(Sha256.hex(token))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));
        if (refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Refresh token expired");
        }
        return issue(refreshToken.getUser());
    }

    @Transactional
    public void deleteByUsername(String username) {
        refreshTokenRepository.deleteByUser(findUser(username));
    }

    // Each user has one active token: issuing a new one replaces any existing token.
    private IssuedRefreshToken issue(User user) {
        refreshTokenRepository.deleteByUser(user);
        String token = generateRandomToken();
        refreshTokenRepository.save(new RefreshToken(
                Sha256.hex(token),
                user,
                LocalDateTime.now().plusSeconds(refreshExpirationMs / 1000)
        ));
        return new IssuedRefreshToken(token, user);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username: " + username));
    }

    private String generateRandomToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
