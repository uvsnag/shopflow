package com.shopflow.order.api;

import com.shopflow.common.web.GlobalExceptionHandler;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.order.config.SecurityConfig;
import com.shopflow.order.domain.Cart;
import com.shopflow.order.domain.Order;
import com.shopflow.order.repository.OrderRepository;
import com.shopflow.order.service.CartService;
import com.shopflow.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level security rules (plan: Testing / API): a customer cannot call admin
 * endpoints, cannot see another user's order, and identity comes only from the
 * gateway headers (NFR-03, BR-06).
 */
@WebMvcTest(controllers = {OrderController.class, AdminOrderController.class, CartController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class OrderApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orderService;
    @MockitoBean OrderRepository orderRepository;
    @MockitoBean CartService cartService;

    @Test
    void noIdentityHeadersIsUnauthorized() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/cart")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerSeesOwnOrders() throws Exception {
        when(orderRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/orders").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk());
    }

    @Test
    void customerCannotUseAdminApi() throws Exception {
        mvc.perform(get("/admin/orders").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/orders/5/ship").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListOrders() throws Exception {
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/admin/orders").header("X-User-Id", "9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void anotherUsersOrderIs404NotForbidden() throws Exception {
        when(orderService.getOwn(5L, 1L)).thenThrow(new NotFoundException("Order 5 not found"));

        mvc.perform(get("/orders/5").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order 5 not found"));
    }

    @Test
    void placeOrderRequiresIdempotencyKey() throws Exception {
        mvc.perform(post("/orders").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeOrderReturnsCreatedOrder() throws Exception {
        Order order = new Order(1L, "abc");
        order.addItem(10L, "Mouse", 250_000L, 1);
        ReflectionTestUtils.setField(order, "id", 77L);
        when(orderService.placeOrder(1L, "abc")).thenReturn(order);

        mvc.perform(post("/orders").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .header("Idempotency-Key", "abc"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(250000))
                .andExpect(jsonPath("$.items[0].productName").value("Mouse"));
    }

    @Test
    void cartQuantityIsValidated() throws Exception {
        when(cartService.putItem(1L, 3L, 2)).thenReturn(new Cart(1L));

        mvc.perform(put("/cart/items/3").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/cart/items/3").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void illegalAdminTransitionIsConflict() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Order 5 cannot go from PENDING to SHIPPED"))
                .when(orderService).changeStatus(eq(5L), any(), anyString());

        mvc.perform(post("/admin/orders/5/ship").header("X-User-Id", "9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isConflict());
    }
}
