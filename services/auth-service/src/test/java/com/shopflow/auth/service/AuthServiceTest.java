package com.shopflow.auth.service;

import com.shopflow.auth.domain.RefreshToken;
import com.shopflow.auth.domain.User;
import com.shopflow.auth.repository.RefreshTokenRepository;
import com.shopflow.auth.repository.UserRepository;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** FR-01..03, NFR-01 (BCrypt), NFR-04 (hashed + rotated refresh tokens). */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock JwtService jwtService;
    @Mock OutboxService outbox;

    final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: fast tests
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, refreshTokenRepository, encoder, jwtService, outbox, Duration.ofDays(7));
    }

    private User storedUser(String password) {
        User u = new User("ann@example.com", encoder.encode(password), "Ann");
        ReflectionTestUtils.setField(u, "id", 12L);
        return u;
    }

    @Test
    void registerHashesPasswordAndAnnouncesTheUser() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 12L);
            return u;
        });

        User user = service.register("ann@example.com", "secret123", "Ann");

        assertNotEquals("secret123", user.getPasswordHash());
        assertTrue(encoder.matches("secret123", user.getPasswordHash()));
        assertEquals(User.Role.CUSTOMER, user.getRole());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).append(eq(EventTypes.TOPIC_USER_EVENTS), eq("12"), isNull(), eq(EventTypes.USER_REGISTERED), payload.capture());
        assertEquals("ann@example.com", payload.getValue().get("email"));
    }

    @Test
    void duplicateEmailIsAConflict() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.register("ann@example.com", "secret123", null));
        verifyNoInteractions(outbox);
    }

    @Test
    void loginIssuesBothTokensAndStoresOnlyAHash() {
        when(userRepository.findByEmail("ann@example.com")).thenReturn(Optional.of(storedUser("secret123")));
        when(jwtService.createAccessToken(any())).thenReturn("access.jwt");

        AuthService.TokenPair tokens = service.login("ann@example.com", "secret123");

        assertEquals("access.jwt", tokens.accessToken());
        ArgumentCaptor<RefreshToken> stored = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(stored.capture());
        assertNotEquals(tokens.refreshToken(), stored.getValue().getTokenHash(), "raw token never stored");
        assertEquals(12L, stored.getValue().getUserId());
        assertTrue(stored.getValue().getExpiresAt().isAfter(Instant.now().plus(Duration.ofDays(6))));
    }

    @Test
    void wrongPasswordOrUnknownEmailLookTheSame() {
        when(userRepository.findByEmail("ann@example.com")).thenReturn(Optional.of(storedUser("secret123")));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        BadRequestException wrongPassword = assertThrows(BadRequestException.class,
                () -> service.login("ann@example.com", "nope"));
        BadRequestException unknownEmail = assertThrows(BadRequestException.class,
                () -> service.login("nobody@example.com", "nope"));
        assertEquals(wrongPassword.getMessage(), unknownEmail.getMessage());
    }

    @Test
    void refreshRotatesTheToken() {
        User user = storedUser("x");
        when(userRepository.findByEmail("ann@example.com")).thenReturn(Optional.of(user));
        when(jwtService.createAccessToken(any())).thenReturn("jwt");
        ArgumentCaptor<RefreshToken> stored = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(stored.capture())).thenAnswer(inv -> inv.getArgument(0));
        AuthService.TokenPair first = service.login("ann@example.com", "x");
        RefreshToken firstRow = stored.getValue();
        when(refreshTokenRepository.findByTokenHash(firstRow.getTokenHash())).thenReturn(Optional.of(firstRow));
        when(userRepository.findById(12L)).thenReturn(Optional.of(user));

        AuthService.TokenPair second = service.refresh(first.refreshToken());

        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertTrue(firstRow.isRevoked(), "old refresh token is dead after use");
        assertThrows(BadRequestException.class, () -> service.refresh(first.refreshToken()), "replay is rejected");
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        RefreshToken row = new RefreshToken(12L, "hash", Instant.now().plus(Duration.ofDays(1)));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(row));

        service.logout("whatever");

        assertTrue(row.isRevoked());
        assertFalse(row.isActive());
    }
}
