package com.shopflow.inventory.api;

import com.shopflow.common.web.GlobalExceptionHandler;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.inventory.config.SecurityConfig;
import com.shopflow.inventory.domain.Stock;
import com.shopflow.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-13 is admin-only; FR-05 stock lookup is public; bodies are validated. */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InventoryApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean InventoryService inventoryService;

    @Test
    void stockLookupIsPublic() throws Exception {
        when(inventoryService.getStock(1L)).thenReturn(new Stock(1L, 50));
        mvc.perform(get("/inventory/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(50));
    }

    @Test
    void unknownStockIs404() throws Exception {
        when(inventoryService.getStock(9L)).thenThrow(new NotFoundException("No stock for product 9"));
        mvc.perform(get("/inventory/9")).andExpect(status().isNotFound());
    }

    @Test
    void customerCannotAddStock() throws Exception {
        mvc.perform(post("/admin/inventory/1/add").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminAddsStockButNotNegativeAmounts() throws Exception {
        when(inventoryService.addStock(1L, 5)).thenReturn(new Stock(1L, 55));

        mvc.perform(post("/admin/inventory/1/add").header("X-User-Id", "9").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(55));
        mvc.perform(post("/admin/inventory/1/add").header("X-User-Id", "9").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }
}
