package com.bank.customer.notification.repository;

import com.bank.customer.notification.domain.CustomerNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, Long> {

    List<CustomerNotification> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** 같은 이벤트가 다시 와도 알림을 두 번 만들지 않기 위한 확인. */
    boolean existsByNotificationTypeAndReferenceId(String notificationType, String referenceId);
}
