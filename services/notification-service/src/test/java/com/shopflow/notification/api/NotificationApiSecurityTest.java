package com.shopflow.notification.api;

import com.shopflow.common.web.GlobalExceptionHandler;
import com.shopflow.notification.config.SecurityConfig;
import com.shopflow.notification.domain.Notification;
import com.shopflow.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-11: a customer only ever sees their own notifications. */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class NotificationApiSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationRepository repository;

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void listIsScopedToTheCaller() throws Exception {
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new Notification(7L, "ORDER", "Order 1 placed", "b"))));

        mvc.perform(get("/notifications").header("X-User-Id", "7").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Order 1 placed"))
                .andExpect(jsonPath("$[0].read").value(false));
    }
}
