package com.bank.customer.notification.service;

import com.bank.customer.notification.domain.CustomerNotification;
import com.bank.customer.notification.repository.CustomerNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 고객 알림 발송·조회.
 *
 * <p><b>앱 알림함이 기본 채널이다.</b> SMS·이메일은 실패하면 사라지지만, 남겨 두면
 * 고객이 접속했을 때 볼 수 있다. 특히 이상거래 지연처럼 "언제까지 취소할 수 있다" 를
 * 알리는 종류는 나중에 다시 확인할 수 있어야 한다.
 *
 * <p><b>외부 발송은 아직 없다.</b> SMS·이메일 게이트웨이가 붙어 있지 않다.
 * 붙이기 전까지 그 채널로 보냈다고 기록하지 않는다 — 보내지도 않고 보냈다고 남기면
 * 나중에 "왜 못 받았느냐" 를 추적할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final CustomerNotificationRepository repository;

    /**
     * 알림을 만든다. 같은 대상에 같은 종류가 이미 있으면 만들지 않는다.
     *
     * <p>Kafka 는 at-least-once 라 재처리가 정상 상황인데, 그대로 두면 고객에게
     * 같은 알림이 여러 번 뜬다. 예외도 안 나고 아무도 모른다.
     *
     * @return 새로 만들었으면 true
     */
    @Transactional
    public boolean notify(Long customerId, String type, String title, String body,
                          String referenceId, OffsetDateTime actionDueAt) {

        if (referenceId != null && repository.existsByNotificationTypeAndReferenceId(type, referenceId)) {
            log.debug("이미 보낸 알림 skip type={} referenceId={}", type, referenceId);
            return false;
        }

        repository.save(CustomerNotification.builder()
                .customerId(customerId)
                .notificationType(type)
                .title(title)
                .body(body)
                .referenceId(referenceId)
                .actionDueAt(actionDueAt)
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public List<CustomerNotification> list(Long customerId) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional
    public void markRead(Long customerId, Long notificationId) {
        repository.findById(notificationId)
                .filter(n -> n.getCustomerId().equals(customerId))
                .ifPresent(CustomerNotification::markRead);
    }
}
