package com.shopflow.notification.messaging;

import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.notification.domain.UserContact;
import com.shopflow.notification.repository.UserContactRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the local user -> e-mail copy up to date from auth-service events. */
@Component
public class UserEventListener {

    private final EventMapper eventMapper;
    private final IdempotencyGuard guard;
    private final UserContactRepository contacts;

    public UserEventListener(EventMapper eventMapper, IdempotencyGuard guard, UserContactRepository contacts) {
        this.eventMapper = eventMapper;
        this.guard = guard;
        this.contacts = contacts;
    }

    @KafkaListener(topics = EventTypes.TOPIC_USER_EVENTS, groupId = "notification-service")
    @Transactional
    public void onUserEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return; // NFR-09
        }
        if (EventTypes.USER_REGISTERED.equals(event.eventType())) {
            Object userId = event.payload().get("userId");
            Object email = event.payload().get("email");
            if (userId != null && email != null) {
                Object fullName = event.payload().get("fullName");
                contacts.save(new UserContact(Long.valueOf(userId.toString()), email.toString(),
                        fullName == null ? null : fullName.toString()));
            }
        }
    }
}
