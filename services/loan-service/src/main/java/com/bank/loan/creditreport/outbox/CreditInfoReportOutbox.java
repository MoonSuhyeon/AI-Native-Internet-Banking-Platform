package com.bank.loan.creditreport.outbox;

import com.bank.loan.common.outbox.AbstractRetryableOutbox;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 신용정보 신고 outbox.
 *
 * 라이프사이클:
 *   PENDING  submit() 시 신고 row 와 함께 적재 (도메인 트랜잭션)
 *   SENT     dispatch 배치가 외부 어댑터 호출 성공 후 전이
 *   FAILED   외부 호출 실패 — attemptNo++, nextAttemptAt 백오프
 *   DEAD     attemptNo >= maxAttempt — 운영자 retry 전까지 보존
 *
 * 같은 crpt_id 에 대해 한 시점에 하나의 outbox row 만 활성 (PENDING 또는 FAILED).
 * 재시도는 본 row 를 갱신 — 새 row 만들지 않는다.
 * 재시도 상태·전이 로직은 {@link AbstractRetryableOutbox} 참고.
 */
@Getter
@Entity
@Table(name = "credit_info_report_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditInfoReportOutbox extends AbstractRetryableOutbox {

    public static final String STATUS_SENT = "SENT";

    public static final int DEFAULT_MAX_ATTEMPT = 5;

    @Column(name = "crpt_id", nullable = false)
    private Long crptId;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Builder
    private CreditInfoReportOutbox(String status, int attemptNo, int maxAttempt, OffsetDateTime nextAttemptAt,
                                   Long crptId) {
        super(status, attemptNo, maxAttempt, nextAttemptAt);
        this.crptId = crptId;
    }

    /**
     * 외부 전송 성공. SENT 로 전이 + sent_at 채움. last_error 는 보존 (감사 추적).
     */
    public void markSent(OffsetDateTime at) {
        this.status = STATUS_SENT;
        this.sentAt = at;
    }
}
