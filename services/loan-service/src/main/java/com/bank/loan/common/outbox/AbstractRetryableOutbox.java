package com.bank.loan.common.outbox;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 재시도·백오프 outbox 의 공통 상위 타입.
 *
 * <p>알림/공통동기화/신용정보신고 outbox 가 공유하던 재시도 상태(status·attemptNo·maxAttempt·
 * nextAttemptAt·lastError)와 전이 로직(markFailed·requeue·delayNextAttempt)을 통합한다.
 * 성공 전이(markSent/markDone)와 성공 상태 코드는 outbox 마다 의미가 달라 하위 클래스에 남긴다.
 *
 * <p>하위 클래스는 {@code @Builder} 를 생성자에 붙여 {@link #AbstractRetryableOutbox(String, int, int, OffsetDateTime)}
 * 로 재시도 상태를 넘긴다(부모를 {@code @SuperBuilder} 로 만들지 않기 위함 — 공유 {@code BaseEntity} 무변경).
 *
 * <p>라이프사이클: PENDING → (성공) | FAILED → DEAD(재시도 상한 초과).
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractRetryableOutbox extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FAILED  = "FAILED";
    public static final String STATUS_DEAD    = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "status", nullable = false, length = 50)
    protected String status;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "max_attempt", nullable = false)
    private int maxAttempt;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** 하위 outbox 생성자에서 재시도 초기 상태를 넘겨받는다. lastError 는 최초 null. */
    protected AbstractRetryableOutbox(String status, int attemptNo, int maxAttempt, OffsetDateTime nextAttemptAt) {
        this.status = status;
        this.attemptNo = attemptNo;
        this.maxAttempt = maxAttempt;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * 외부 처리 실패. attemptNo++, 백오프 nextAttemptAt = now + 2^attemptNo 분 (1, 2, 4, 8, 16 ...).
     * attemptNo 가 maxAttempt 에 도달하면 DEAD 로 전이.
     */
    public void markFailed(String error, OffsetDateTime now) {
        this.attemptNo = this.attemptNo + 1;
        this.lastError = truncate(error, 500);
        if (this.attemptNo >= this.maxAttempt) {
            this.status = STATUS_DEAD;
            this.nextAttemptAt = now;
        } else {
            this.status = STATUS_FAILED;
            this.nextAttemptAt = now.plusMinutes(1L << Math.min(this.attemptNo, 10));
        }
    }

    /** 운영자 재전송. attemptNo 리셋 + 즉시 PENDING. */
    public void requeue(OffsetDateTime now) {
        this.status = STATUS_PENDING;
        this.attemptNo = 0;
        this.nextAttemptAt = now;
        this.lastError = null;
    }

    /**
     * dispatch 배치가 한 row 를 처리하기 직전, 같은 배치 사이클 내 중복 픽업을 막기 위해
     * 일시적으로 다음 시도 시각을 한 사이클 뒤로 미뤄둔다. 결과 수신 후 성공/실패 전이가 덮어쓴다.
     */
    public void delayNextAttempt(OffsetDateTime nextAt) {
        this.nextAttemptAt = nextAt;
    }

    protected static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
