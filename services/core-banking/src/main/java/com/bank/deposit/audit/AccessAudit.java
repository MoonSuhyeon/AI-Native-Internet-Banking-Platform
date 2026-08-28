package com.bank.deposit.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 수신 데이터 열람 한 건.
 *
 * <p>거절도 남긴다. 성공만 남기면 <b>"봐서는 안 되는 것을 보려 했다"</b> 가
 * 기록에서 사라진다 — 감사에서 정작 필요한 것은 그쪽이다.
 */
@Entity
@Table(name = "deposit_access_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deposit_access_log_id")
    private Long id;

    @Column(name = "actor_type", length = 20, nullable = false)
    private String actorType;

    @Column(name = "actor_employee_id")
    private Long actorEmployeeId;

    @Column(name = "actor_customer_id", length = 30)
    private String actorCustomerId;

    @Column(name = "actor_service", length = 60)
    private String actorService;

    @Column(name = "target_customer_id", length = 30)
    private String targetCustomerId;

    @Column(name = "target_account_id")
    private Long targetAccountId;

    @Column(name = "access_action_code", length = 40, nullable = false)
    private String accessActionCode;

    @Column(name = "access_reason", length = 500)
    private String accessReason;

    @Column(name = "result_code", length = 20, nullable = false)
    private String resultCode;

    @Column(name = "denied_reason", length = 200)
    private String deniedReason;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "accessed_at", nullable = false)
    private OffsetDateTime accessedAt;

    @Builder
    private AccessAudit(String actorType, Long actorEmployeeId, String actorCustomerId,
                        String actorService, String targetCustomerId, Long targetAccountId,
                        String accessActionCode, String accessReason, String resultCode,
                        String deniedReason, String traceId, OffsetDateTime accessedAt) {
        this.actorType = actorType;
        this.actorEmployeeId = actorEmployeeId;
        this.actorCustomerId = actorCustomerId;
        this.actorService = actorService;
        this.targetCustomerId = targetCustomerId;
        this.targetAccountId = targetAccountId;
        this.accessActionCode = accessActionCode;
        this.accessReason = accessReason;
        this.resultCode = resultCode;
        this.deniedReason = deniedReason;
        this.traceId = traceId;
        this.accessedAt = accessedAt;
    }
}
