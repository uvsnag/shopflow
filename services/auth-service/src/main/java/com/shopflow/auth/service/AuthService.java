package com.shopflow.auth.service;

import com.shopflow.auth.domain.RefreshToken;
import com.shopflow.auth.domain.User;
import com.shopflow.auth.repository.RefreshTokenRepository;
import com.shopflow.auth.repository.UserRepository;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Duration refreshTtl;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       @Value("${shopflow.jwt.refresh-ttl:7d}") Duration refreshTtl) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTtl = refreshTtl;
    }

    public record TokenPair(String accessToken, String refreshToken) {}

    @Transactional
    public User register(String email, String password, String fullName) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        // FR-01, NFR-01: BCrypt hash
        return userRepository.save(new User(email, passwordEncoder.encode(password), fullName));
    }

    @Transactional
    public TokenPair login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("Invalid credentials");
        }
        return issueTokens(user);
    }

    /** NFR-04: refresh tokens are rotated — the old one is revoked on use. */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .filter(RefreshToken::isActive)
                .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
        stored.revoke();
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BadRequestException("User no longer exists"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .ifPresent(RefreshToken::revoke); // FR-03
    }

    private TokenPair issueTokens(User user) {
        String refresh = UUID.randomUUID() + "." + UUID.randomUUID();
        refreshTokenRepository.save(new RefreshToken(user.getId(), hash(refresh), Instant.now().plus(refreshTtl)));
        return new TokenPair(jwtService.createAccessToken(user), refresh);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
