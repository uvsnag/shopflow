package com.shopflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The context starts (Redis/JWKS are contacted lazily) and every service has a route. */
@SpringBootTest(properties = "management.tracing.enabled=false")
class ApiGatewayRoutesTest {

    @Autowired RouteLocator routeLocator;

    @Test
    void everyServiceIsRouted() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        Set<String> ids = routes.stream().map(Route::getId).collect(Collectors.toSet());
        assertEquals(Set.of("auth-login-rate-limited", "auth", "products", "inventory", "orders",
                "payments", "notifications"), ids);
        Route login = routes.stream().filter(r -> r.getId().equals("auth-login-rate-limited")).findFirst().orElseThrow();
        assertTrue(login.getFilters().stream().anyMatch(f -> f.toString().contains("RequestRateLimiter")),
                "NFR-05: login is rate limited");
    }
}
