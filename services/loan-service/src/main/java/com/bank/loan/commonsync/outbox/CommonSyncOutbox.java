package com.bank.loan.commonsync.outbox;

import com.bank.loan.common.outbox.AbstractRetryableOutbox;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * common_db write-through outbox (loan_db 적재).
 *
 * 라이프사이클:
 *   PENDING  도메인 트랜잭션에서 적재
 *   DONE     CommonSyncDispatchService 가 common_db upsert + 브리지 백필 성공 후 전이
 *   FAILED   upsert/백필 실패 — attemptNo++, nextAttemptAt 백오프
 *   DEAD     attemptNo >= maxAttempt
 *
 * target_type_cd: PRODUCT / CONTRACT / TRANSACTION
 * source_id     : loan_db 원본 PK (prod_id / cntr_id / exec_id or rtx_id)
 * source_no     : common_db 자연키 (product_cd / contract_no / transaction_no)
 * payload       : 동기화에 필요한 필드 스냅샷 (JSONB)
 * common_id     : upsert 후 common_db 가 채번한 PK — 브리지 백필 대상 값
 * 재시도 상태·전이 로직은 {@link AbstractRetryableOutbox} 참고.
 */
@Getter
@Entity
@Table(name = "common_sync_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonSyncOutbox extends AbstractRetryableOutbox {

    public static final String TARGET_PRODUCT     = "PRODUCT";
    public static final String TARGET_CONTRACT    = "CONTRACT";
    public static final String TARGET_TRANSACTION = "TRANSACTION";

    public static final String STATUS_DONE = "DONE";

    public static final int DEFAULT_MAX_ATTEMPT = 5;

    @Column(name = "target_type_cd", length = 20, nullable = false)
    private String targetTypeCd;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_no", length = 50)
    private String sourceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "common_id")
    private Long commonId;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "idempotency_key", length = 100, nullable = false, unique = true)
    private String idempotencyKey;

    @Builder
    private CommonSyncOutbox(String status, int attemptNo, int maxAttempt, OffsetDateTime nextAttemptAt,
                             String targetTypeCd, Long sourceId, String sourceNo, String payload,
                             String idempotencyKey) {
        super(status, attemptNo, maxAttempt, nextAttemptAt);
        this.targetTypeCd = targetTypeCd;
        this.sourceId = sourceId;
        this.sourceNo = sourceNo;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
    }

    public void markDone(Long resolvedCommonId, OffsetDateTime at) {
        this.status = STATUS_DONE;
        this.commonId = resolvedCommonId;
        this.syncedAt = at;
    }
}
