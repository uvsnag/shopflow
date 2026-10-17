package com.shopflow.notification.api;

import com.shopflow.common.security.CurrentUser;
import com.shopflow.notification.domain.Notification;
import com.shopflow.notification.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** FR-11: a customer sees their own in-app notifications. */
@RestController
@RequestMapping("/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    public record NotificationResponse(Long id, String type, String title, String body,
                                       boolean read, java.time.Instant createdAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                    n.isRead(), n.getCreatedAt());
        }
    }

    @GetMapping
    public List<NotificationResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return repository.findByUserIdOrderByCreatedAtDesc(CurrentUser.id(), PageRequest.of(page, size))
                .map(NotificationResponse::from).getContent();
    }
}
