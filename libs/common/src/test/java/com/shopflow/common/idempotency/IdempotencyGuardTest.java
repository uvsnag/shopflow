package com.shopflow.common.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NFR-09: the same eventId is processed once. */
class IdempotencyGuardTest {

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final IdempotencyGuard guard = new IdempotencyGuard(repository);
    private final UUID eventId = UUID.randomUUID();

    @Test
    void firstDeliveryIsProcessed() {
        when(repository.existsById(eventId)).thenReturn(false);
        assertTrue(guard.markProcessed(eventId));
        verify(repository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    void secondDeliveryIsSkipped() {
        when(repository.existsById(eventId)).thenReturn(true);
        assertFalse(guard.markProcessed(eventId));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void losingTheInsertRaceCountsAsDuplicate() {
        when(repository.existsById(eventId)).thenReturn(false);
        when(repository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        assertFalse(guard.markProcessed(eventId));
    }
}
