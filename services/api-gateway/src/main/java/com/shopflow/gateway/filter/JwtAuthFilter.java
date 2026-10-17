package com.shopflow.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.util.List;

/**
 * Checks the RS256 JWT against auth-service's JWKS (NFR-02) and forwards the
 * identity as X-User-Id / X-User-Role headers (NFR-03). Public paths pass
 * through untouched.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/products",
            "/api/v1/payments/webhook"
    );

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    public JwtAuthFilter(@Value("${shopflow.jwks-url:http://localhost:8081/.well-known/jwks.json}") String jwksUrl)
            throws Exception {
        JWKSource<SecurityContext> keySource = JWKSourceBuilder
                .create(new URL(jwksUrl))
                .retrying(true)
                .build();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
        this.jwtProcessor = processor;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isPublic(path, exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "GET")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        try {
            JWTClaimsSet claims = jwtProcessor.process(authHeader.substring(7), null);
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Role", claims.getStringClaim("role"))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception invalidToken) {
            return unauthorized(exchange);
        }
    }

    private boolean isPublic(String path, String method) {
        // product browsing is public for GET only (FR-04, FR-05)
        if ("GET".equals(method) && path.startsWith("/api/v1/products")) {
            return true;
        }
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith)
                && !path.startsWith("/api/v1/products"); // admin product paths handled above
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
