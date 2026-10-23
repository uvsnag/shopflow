package com.shopflow.common.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** NFR-09: the same eventId is processed once, decided by one atomic insert. */
class IdempotencyGuardTest {

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final IdempotencyGuard guard = new IdempotencyGuard(repository);
    private final UUID eventId = UUID.randomUUID();

    @Test
    void firstDeliveryClaimsTheIdAndIsProcessed() {
        when(repository.insertIfAbsent(eventId)).thenReturn(1);
        assertTrue(guard.markProcessed(eventId));
    }

    @Test
    void secondDeliveryIsSkipped() {
        when(repository.insertIfAbsent(eventId)).thenReturn(0);
        assertFalse(guard.markProcessed(eventId));
    }

    @Test
    void noReadBeforeWrite() {
        when(repository.insertIfAbsent(eventId)).thenReturn(1);
        guard.markProcessed(eventId);
        verify(repository, never()).existsById(any());
        verify(repository, never()).findById(any());
        verify(repository, only()).insertIfAbsent(eventId);
    }
}
