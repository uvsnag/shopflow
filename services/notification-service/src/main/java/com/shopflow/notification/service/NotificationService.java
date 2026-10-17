package com.shopflow.notification.service;

import com.shopflow.notification.domain.Notification;
import com.shopflow.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-11: saves an in-app notification and tries to send an email (MailHog
 * locally). Email failure never fails the saga — it is logged and skipped.
 *
 * TODO: the user's email is not in the events; either add it to the event
 * payload or look it up. For now emails go to a per-user placeholder address.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final JavaMailSender mailSender;

    public NotificationService(NotificationRepository repository, JavaMailSender mailSender) {
        this.repository = repository;
        this.mailSender = mailSender;
    }

    @Transactional
    public void notifyUser(Long userId, String type, String title, String body) {
        repository.save(new Notification(userId, type, title, body));
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom("noreply@shopflow.local");
            mail.setTo("user-" + userId + "@shopflow.local");
            mail.setSubject(title);
            mail.setText(body);
            mailSender.send(mail);
        } catch (Exception e) {
            log.warn("Email send failed for user {} — in-app notification still saved", userId, e);
        }
    }
}
