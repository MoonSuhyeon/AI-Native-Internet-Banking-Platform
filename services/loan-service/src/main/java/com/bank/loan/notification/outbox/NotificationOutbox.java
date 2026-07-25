package com.bank.loan.notification.outbox;

import com.bank.loan.common.outbox.AbstractRetryableOutbox;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

/**
 * 알림 outbox. listener 가 이벤트 수신 시 (sms/kakao/email/push 각각) 한 row 씩 적재한다.
 * dispatch 배치가 채널별 어댑터에 위임해 외부 송신을 수행.
 *
 * 라이프사이클: PENDING → SENT (성공) | FAILED → DEAD (재시도 상한 초과).
 * 멱등 키 (`eventTypeCd + referenceId + channelCd`) 로 동일 이벤트 재발행을 차단한다.
 * 재시도 상태·전이 로직은 {@link AbstractRetryableOutbox} 참고.
 */
@Getter
@Entity
@Table(name = "notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends AbstractRetryableOutbox {

    public static final String STATUS_SENT = "SENT";

    public static final int DEFAULT_MAX_ATTEMPT = 3;

    /** 이벤트 종류. listener 가 publish 한 이벤트별 식별자. (APPLICATION_SUBMITTED, INSTALLMENT_PAID, ...) */
    @Column(name = "event_type_cd", nullable = false, length = 50)
    private String eventTypeCd;

    /** 이벤트에 묶인 도메인 PK (applId / cntrId / rtxId / clos_id 등). */
    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    /** 발송 채널. SMS / KAKAO_ALIMTALK / EMAIL / APP_PUSH. */
    @Column(name = "channel_cd", nullable = false, length = 50)
    private String channelCd;

    /** 템플릿 렌더 데이터. PII 가 포함될 수 있음 — 11 plan 의 PII 암호화 적용 대상. */
    @Column(name = "payload", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** 동일 이벤트 재발행 차단용. `eventTypeCd:referenceId:channelCd`. UNIQUE. */
    @Column(name = "idempotency_key", nullable = false, length = 200, unique = true)
    private String idempotencyKey;

    @Builder
    private NotificationOutbox(String status, int attemptNo, int maxAttempt, OffsetDateTime nextAttemptAt,
                               String eventTypeCd, Long referenceId, String channelCd, String payload,
                               String idempotencyKey) {
        super(status, attemptNo, maxAttempt, nextAttemptAt);
        this.eventTypeCd = eventTypeCd;
        this.referenceId = referenceId;
        this.channelCd = channelCd;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
    }

    public static String idempotencyKeyOf(String eventTypeCd, Long referenceId, String channelCd) {
        return eventTypeCd + ":" + referenceId + ":" + channelCd;
    }

    public void markSent(OffsetDateTime at) {
        this.status = STATUS_SENT;
        this.sentAt = at;
    }
}
