package com.shopflow.auth.api;

import com.shopflow.auth.domain.User;
import com.shopflow.auth.service.AuthService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record RegisterRequest(@Email @NotBlank String email,
                                  @NotBlank @Size(min = 8) String password,
                                  String fullName) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody @jakarta.validation.Valid RegisterRequest req) {
        User user = authService.register(req.email(), req.password(), req.fullName());
        return Map.of("id", user.getId(), "email", user.getEmail());
    }

    @PostMapping("/login")
    public AuthService.TokenPair login(@RequestBody @jakarta.validation.Valid LoginRequest req) {
        return authService.login(req.email(), req.password());
    }

    @PostMapping("/refresh")
    public AuthService.TokenPair refresh(@RequestBody @jakarta.validation.Valid RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @jakarta.validation.Valid RefreshRequest req) {
        authService.logout(req.refreshToken());
    }
}
