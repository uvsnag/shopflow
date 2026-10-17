package com.shopflow.product.api;

import com.shopflow.common.web.GlobalExceptionHandler;
import com.shopflow.product.api.dto.ProductDtos.ProductResponse;
import com.shopflow.product.config.SecurityConfig;
import com.shopflow.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-04/05 public; FR-12 admin only (NFR-03 defense in depth); page size capped. */
@WebMvcTest(controllers = {ProductController.class, AdminProductController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ProductApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean ProductService productService;

    @Test
    void catalogIsPublicAndPageSizeIsCapped() throws Exception {
        when(productService.list(isNull(), isNull(), eq(0), eq(100)))
                .thenReturn(new ProductService.PageResponse(List.of(), 0, 100, 0, 0));

        mvc.perform(get("/products").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void customerCannotManageProducts() throws Exception {
        String body = "{\"name\":\"X\",\"price\":10}";
        mvc.perform(post("/admin/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/products").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/products/1").header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesProductsWithValidation() throws Exception {
        when(productService.create(any())).thenReturn(new ProductResponse(9L, 1L, "X", null, 10L, null, true));

        mvc.perform(post("/admin/products").header("X-User-Id", "9").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"price\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/products").header("X-User-Id", "9").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":10,\"categoryId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9));
    }
}
