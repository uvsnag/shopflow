package com.shopflow.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * notification-service's own copy of "which e-mail belongs to this user",
 * filled from auth-service's UserRegistered events. No call to auth-service
 * and no shared database (ADR 0002).
 */
@Entity
@Table(name = "user_contacts")
public class UserContact {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserContact() {
    }

    public UserContact(Long userId, String email, String fullName) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
    }

    public Long getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public Instant getUpdatedAt() { return updatedAt; }
}
