package com.shopflow.notification.repository;

import com.shopflow.notification.domain.UserContact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserContactRepository extends JpaRepository<UserContact, Long> {
}
