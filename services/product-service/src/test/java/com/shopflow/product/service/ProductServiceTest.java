package com.shopflow.product.service;

import com.shopflow.common.web.NotFoundException;
import com.shopflow.product.api.dto.ProductDtos.ProductRequest;
import com.shopflow.product.domain.Product;
import com.shopflow.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository repository;
    @InjectMocks ProductService service;

    @Test
    void listPicksTheRightQueryForTheFilters() {
        Product p = new Product(1L, "Mouse", "d", 100L, null);
        when(repository.findByActiveTrue(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p)));
        when(repository.findByActiveTrueAndNameContainingIgnoreCase(eq("mou"), any())).thenReturn(new PageImpl<>(List.of(p)));
        when(repository.findByActiveTrueAndCategoryId(eq(1L), any())).thenReturn(new PageImpl<>(List.of(p)));
        when(repository.findByActiveTrueAndCategoryIdAndNameContainingIgnoreCase(eq(1L), eq("mou"), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertEquals(1, service.list(" ", null, 0, 20).items().size());
        assertEquals(1, service.list("mou", null, 0, 20).items().size());
        assertEquals(1, service.list(null, 1L, 0, 20).items().size());
        assertEquals(0, service.list("mou", 1L, 0, 20).items().size());
        assertEquals(1, service.list(null, null, 0, 20).totalItems());
    }

    @Test
    void hiddenProductIsNotFoundForCustomers() {
        Product hidden = new Product(1L, "Old", "d", 100L, null);
        hidden.hide();
        when(repository.findById(5L)).thenReturn(Optional.of(hidden));
        when(repository.findById(6L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get(5L));
        assertThrows(NotFoundException.class, () -> service.get(6L));
    }

    @Test
    void updateOnlyTouchesGivenFields() {
        Product p = new Product(1L, "Mouse", "desc", 100L, "img");
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service.update(1L, new ProductRequest(null, "Better Mouse", null, 120L, null, null));

        assertEquals("Better Mouse", p.getName());
        assertEquals("desc", p.getDescription());
        assertEquals(120L, p.getPrice());
        assertEquals(1L, p.getCategoryId());
        assertTrue(p.isActive());
    }

    @Test
    void hideIsSoftDelete() {
        Product p = new Product(1L, "Mouse", "desc", 100L, null);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service.hide(1L);

        assertFalse(p.isActive());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }
}
