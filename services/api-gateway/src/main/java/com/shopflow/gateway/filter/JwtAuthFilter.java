package com.shopflow.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * Checks the RS256 JWT against auth-service's JWKS (NFR-02) and forwards the
 * identity as X-User-Id / X-User-Role headers (NFR-03). Those two headers are
 * ALWAYS stripped from the incoming request first — only the gateway may set
 * them, or any client could impersonate any user on public routes.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/payments/webhook"
    );

    /** Must match the issuer auth-service puts in every token. */
    static final String ISSUER = "shopflow-auth";
    /** Tolerated clock difference between auth-service and the gateway (nimbus default would be 60 s). */
    static final int MAX_CLOCK_SKEW_SECONDS = 10;

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    @Autowired   // two constructors: tell Spring which one to use
    public JwtAuthFilter(@Value("${shopflow.jwks-url:http://localhost:8081/.well-known/jwks.json}") String jwksUrl)
            throws Exception {
        // keys are fetched lazily on the first token and cached/refreshed by nimbus
        this(JWKSourceBuilder.<SecurityContext>create(new URL(jwksUrl)).retrying(true).build());
    }

    /** Visible for tests: any key source, e.g. an in-memory JWK set. */
    JwtAuthFilter(JWKSource<SecurityContext> keySource) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
        // exp is enforced (with a small skew), issuer must match, sub/role must be present
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(ISSUER).build(), Set.of("sub", "exp", "role"));
        claimsVerifier.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        this.jwtProcessor = processor;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // A CORS preflight (OPTIONS + Origin + Access-Control-Request-Method) never carries
        // the Authorization header, so it must not be answered with 401. The globalcors
        // config normally handles it before any route filter runs; this is the safety net.
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name() : "GET";

        if (isPublic(path, method)) {
            ServerHttpRequest cleaned = exchange.getRequest().mutate()
                    .headers(h -> {
                        h.remove(USER_ID_HEADER);
                        h.remove(USER_ROLE_HEADER);
                    })
                    .build();
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }
        String token = authHeader.substring(7);

        // nimbus fetches/refreshes the JWKS over blocking HTTP — keep it off
        // the Netty event loop
        return Mono.fromCallable(() -> jwtProcessor.process(token, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claims -> {
                    String role;
                    try {
                        role = claims.getStringClaim("role");
                    } catch (java.text.ParseException e) {
                        return unauthorized(exchange);
                    }
                    if (role == null || claims.getSubject() == null) {
                        return unauthorized(exchange);
                    }
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .headers(h -> {
                                h.set(USER_ID_HEADER, claims.getSubject());
                                h.set(USER_ROLE_HEADER, role);
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .onErrorResume(invalidToken -> unauthorized(exchange));
    }

    static boolean isPublic(String path, String method) {
        // product browsing is public for GET only (FR-04, FR-05);
        // /api/v1/admin/products stays protected because it doesn't start
        // with /api/v1/products
        if ("GET".equals(method) && path.startsWith("/api/v1/products")) {
            return true;
        }
        // stock-left on product detail is public too (FR-05);
        // /api/v1/admin/inventory does not match this prefix
        if ("GET".equals(method) && path.startsWith("/api/v1/inventory")) {
            return true;
        }
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
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
