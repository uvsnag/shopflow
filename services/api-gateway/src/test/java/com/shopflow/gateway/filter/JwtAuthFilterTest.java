package com.shopflow.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** NFR-02/03: only a valid RS256 token gets through, and identity headers can never be spoofed. */
class JwtAuthFilterTest {

    static RSAKey signingKey;
    static RSAKey otherKey;
    static JwtAuthFilter filter;

    @BeforeAll
    static void keys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k2").generate();
        filter = new JwtAuthFilter(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey.toPublicJWK())));
    }

    private static String token(RSAKey key, String role, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("12").issuer(JwtAuthFilter.ISSUER).claim("role", role).expirationTime(Date.from(expiry)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** Runs the filter and returns the request the downstream chain saw (null when short-circuited). */
    private static ServerWebExchange run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstream.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        ServerWebExchange seen = downstream.get();
        if (seen == null) {
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
        return seen;
    }

    @Test
    void validTokenBecomesIdentityHeaders() throws Exception {
        ServerWebExchange seen = run(MockServerHttpRequest.get("/api/v1/orders")
                .header("Authorization", "Bearer " + token(signingKey, "CUSTOMER", Instant.now().plusSeconds(600)))
                .header(JwtAuthFilter.USER_ID_HEADER, "1")   // spoof attempt
                .header(JwtAuthFilter.USER_ROLE_HEADER, "ADMIN")
                .build());

        assertNotNull(seen);
        assertEquals("12", seen.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ID_HEADER));
        assertEquals("CUSTOMER", seen.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ROLE_HEADER));
    }

    @Test
    void missingExpiredOrForeignTokensAreRejected() throws Exception {
        assertNull(run(MockServerHttpRequest.post("/api/v1/orders").build()));
        assertNull(run(MockServerHttpRequest.get("/api/v1/orders")
                .header("Authorization", "Bearer " + token(signingKey, "CUSTOMER", Instant.now().minusSeconds(JwtAuthFilter.MAX_CLOCK_SKEW_SECONDS + 5))).build()));
        assertNull(run(MockServerHttpRequest.get("/api/v1/orders")
                .header("Authorization", "Bearer " + token(otherKey, "CUSTOMER", Instant.now().plusSeconds(600))).build()));
        assertNull(run(MockServerHttpRequest.get("/api/v1/admin/products")
                .header("Authorization", "Bearer garbage").build()));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("12").issuer("someone-else").claim("role", "ADMIN")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        assertNull(run(MockServerHttpRequest.get("/api/v1/orders").header("Authorization", "Bearer " + jwt.serialize()).build()));
    }

    @Test
    void publicRoutesPassButSpoofedHeadersAreStripped() {
        ServerWebExchange seen = run(MockServerHttpRequest.get("/api/v1/products/1")
                .header(JwtAuthFilter.USER_ID_HEADER, "1")
                .header(JwtAuthFilter.USER_ROLE_HEADER, "ADMIN")
                .build());

        assertNotNull(seen);
        assertNull(seen.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ID_HEADER));
        assertNull(seen.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_ROLE_HEADER));
    }

    @Test
    void publicPathTable() {
        assertTrue(JwtAuthFilter.isPublic("/api/v1/products", "GET"));
        assertTrue(JwtAuthFilter.isPublic("/api/v1/inventory/3", "GET"));
        assertTrue(JwtAuthFilter.isPublic("/api/v1/auth/login", "POST"));
        assertTrue(JwtAuthFilter.isPublic("/api/v1/payments/webhook", "POST"));
        assertFalse(JwtAuthFilter.isPublic("/api/v1/admin/products", "GET"));
        assertFalse(JwtAuthFilter.isPublic("/api/v1/admin/inventory/3/add", "POST"));
        assertFalse(JwtAuthFilter.isPublic("/api/v1/auth/logout", "POST"));
        assertFalse(JwtAuthFilter.isPublic("/api/v1/payments", "POST"));
        assertFalse(JwtAuthFilter.isPublic("/api/v1/orders", "GET"));
    }
}
