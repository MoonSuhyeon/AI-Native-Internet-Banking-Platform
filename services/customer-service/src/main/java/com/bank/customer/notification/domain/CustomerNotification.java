package com.bank.customer.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 고객에게 보낼 알림 한 건.
 *
 * <p><b>왜 저장하는가.</b> SMS·푸시는 실패하면 사라진다. 특히 이상거래 지연처럼
 * "언제까지 취소할 수 있다" 를 알리는 종류는 고객이 나중에 다시 확인할 수 있어야 한다.
 * 앱에 남겨 두면 발송 채널이 실패해도 고객이 접속했을 때 볼 수 있다.
 */
@Entity
@Table(name = "customer_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "notification_type", nullable = false, length = 40)
    private String notificationType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    /** 이 알림이 가리키는 대상(결제지시번호 등). 중복 방지 키이기도 하다. */
    @Column(name = "reference_id", length = 64)
    private String referenceId;

    /**
     * 조치 기한. 지나면 화면에서 취소 버튼을 감춰야 한다 —
     * 이미 실행된 거래에 취소 버튼을 남겨 두면 고객이 눌러 보고 실패를 겪는다.
     */
    @Column(name = "action_due_at")
    private OffsetDateTime actionDueAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Builder
    private CustomerNotification(Long customerId, String notificationType, String title,
                                 String body, String referenceId, OffsetDateTime actionDueAt) {
        this.customerId = customerId;
        this.notificationType = notificationType;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.actionDueAt = actionDueAt;
        this.read = false;
        this.createdAt = OffsetDateTime.now();
    }

    public void markRead() {
        if (!this.read) {
            this.read = true;
            this.readAt = OffsetDateTime.now();
        }
    }
}
