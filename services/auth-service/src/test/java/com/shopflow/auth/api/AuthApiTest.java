package com.shopflow.auth.api;

import com.shopflow.auth.config.SecurityConfig;
import com.shopflow.auth.service.AuthService;
import com.shopflow.auth.service.JwtService;
import com.shopflow.common.web.ConflictException;
import com.shopflow.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, JwksController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;
    @MockitoBean JwtService jwtService;

    @Test
    void registerValidatesInput() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void duplicateRegistrationIsConflict() throws Exception {
        when(authService.register(anyString(), anyString(), any())).thenThrow(new ConflictException("Email already registered"));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Email already registered"));
    }

    @Test
    void loginReturnsTokenPair() throws Exception {
        when(authService.login("ann@example.com", "secret123"))
                .thenReturn(new AuthService.TokenPair("access", "refresh"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    void logoutNeedsAnIdentity() throws Exception {
        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isNoContent());
        verify(authService).logout("r");
    }

    @Test
    void jwksIsPublic() throws Exception {
        when(jwtService.jwks()).thenReturn(Map.of("keys", java.util.List.of()));
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray());
    }
}
