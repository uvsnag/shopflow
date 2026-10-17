package com.shopflow.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.notification.domain.UserContact;
import com.shopflow.notification.repository.UserContactRepository;
import com.shopflow.notification.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** FR-11: every status change becomes a notification for the right user; duplicates and unknown users are skipped. */
class NotificationEventListenerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final UserContactRepository contacts = mock(UserContactRepository.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(new EventMapper(objectMapper), guard, notificationService);
    private final UserEventListener userListener =
            new UserEventListener(new EventMapper(objectMapper), guard, contacts);

    @Test
    void orderAndPaymentEventsNotifyTheOrderOwner() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onEvent(envelope(EventTypes.ORDER_CREATED, Map.of("userId", 7)));
        listener.onEvent(envelope(EventTypes.PAYMENT_FAILED, Map.of("userId", 7, "reason", "provider declined")));
        listener.onEvent(envelope(EventTypes.ORDER_DELIVERED, Map.of("userId", 7)));

        verify(notificationService).notifyUser(eq(7L), eq("ORDER"), contains("placed"), anyString());
        verify(notificationService).notifyUser(eq(7L), eq("PAYMENT"), contains("failed"), contains("provider declined"));
        verify(notificationService).notifyUser(eq(7L), eq("ORDER"), contains("delivered"), anyString());
    }

    @Test
    void eventsWithoutUserOrDuplicatesAreIgnored() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true, false);

        listener.onEvent(envelope(EventTypes.ORDER_CREATED, Map.of()));           // no userId
        listener.onEvent(envelope(EventTypes.ORDER_CREATED, Map.of("userId", 7))); // duplicate

        verifyNoInteractions(notificationService);
    }

    @Test
    void userRegisteredIsRememberedForEmails() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        userListener.onUserEvent(objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(),
                EventTypes.USER_REGISTERED, Instant.now(), null,
                Map.of("userId", 7, "email", "ann@example.com", "fullName", "Ann"))));

        verify(contacts).save(argThat((UserContact c) -> c.getUserId() == 7L && c.getEmail().equals("ann@example.com")));
    }

    private String envelope(String type, Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(), type, Instant.now(), "42", payload));
    }
}
