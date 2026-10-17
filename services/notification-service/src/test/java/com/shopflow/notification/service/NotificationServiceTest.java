package com.shopflow.notification.service;

import com.shopflow.notification.domain.Notification;
import com.shopflow.notification.domain.UserContact;
import com.shopflow.notification.repository.NotificationRepository;
import com.shopflow.notification.repository.UserContactRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** FR-11 / NFR-13: in-app message always saved; e-mail goes to the known address and may fail quietly. */
class NotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final UserContactRepository contacts = mock(UserContactRepository.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final NotificationService service = new NotificationService(repository, contacts, mailSender);

    @Test
    void emailGoesToTheRegisteredAddress() {
        when(contacts.findById(7L)).thenReturn(Optional.of(new UserContact(7L, "ann@example.com", "Ann")));

        service.notifyUser(7L, "ORDER", "Order 1 placed", "body");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertArrayEquals(new String[]{"ann@example.com"}, mail.getValue().getTo());
        assertEquals("Order 1 placed", mail.getValue().getSubject());
        verify(repository).save(any(Notification.class));
    }

    @Test
    void unknownUserGetsPlaceholderAddress() {
        when(contacts.findById(8L)).thenReturn(Optional.empty());

        service.notifyUser(8L, "ORDER", "t", "b");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertArrayEquals(new String[]{"user-8@shopflow.local"}, mail.getValue().getTo());
    }

    @Test
    void mailFailureNeverBreaksTheSaga() {
        when(contacts.findById(7L)).thenReturn(Optional.empty());
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> service.notifyUser(7L, "ORDER", "t", "b"));
        verify(repository).save(any(Notification.class));
    }
}
