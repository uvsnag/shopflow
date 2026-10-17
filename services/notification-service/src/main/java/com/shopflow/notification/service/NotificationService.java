package com.shopflow.notification.service;

import com.shopflow.notification.domain.Notification;
import com.shopflow.notification.domain.UserContact;
import com.shopflow.notification.repository.NotificationRepository;
import com.shopflow.notification.repository.UserContactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-11: saves an in-app notification and sends an e-mail (MailHog locally).
 * The address comes from the local user_contacts copy (UserRegistered events);
 * users we have never heard of (e.g. the seeded admin) get a placeholder.
 * E-mail failure never fails the saga — it is logged and skipped.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final UserContactRepository contacts;
    private final JavaMailSender mailSender;

    public NotificationService(NotificationRepository repository, UserContactRepository contacts,
                               JavaMailSender mailSender) {
        this.repository = repository;
        this.contacts = contacts;
        this.mailSender = mailSender;
    }

    @Transactional
    public void notifyUser(Long userId, String type, String title, String body) {
        repository.save(new Notification(userId, type, title, body));
        String to = contacts.findById(userId).map(UserContact::getEmail)
                .orElse("user-" + userId + "@shopflow.local");
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom("noreply@shopflow.local");
            mail.setTo(to);
            mail.setSubject(title);
            mail.setText(body);
            mailSender.send(mail);
        } catch (Exception e) {
            log.warn("Email send failed for user {} — in-app notification still saved", userId, e);
        }
    }
}
