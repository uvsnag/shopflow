package com.shopflow.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/** NFR-03: the identity the gateway forwards becomes the Spring Security principal. */
class GatewayHeaderAuthFilterTest {

    private final GatewayHeaderAuthFilter filter = new GatewayHeaderAuthFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void headersBecomePrincipalAndRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader(GatewayHeaderAuthFilter.USER_ID_HEADER, "5");
        request.addHeader(GatewayHeaderAuthFilter.USER_ROLE_HEADER, "ADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(5L, CurrentUser.id());
        assertTrue(CurrentUser.isAdmin());
    }

    @Test
    void customerIsNotAdmin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader(GatewayHeaderAuthFilter.USER_ID_HEADER, "6");
        request.addHeader(GatewayHeaderAuthFilter.USER_ROLE_HEADER, "CUSTOMER");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(6L, CurrentUser.id());
        assertFalse(CurrentUser.isAdmin());
    }

    @Test
    void noHeadersMeansAnonymous() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertThrows(IllegalStateException.class, CurrentUser::id);
        assertFalse(CurrentUser.isAdmin());
    }
}
