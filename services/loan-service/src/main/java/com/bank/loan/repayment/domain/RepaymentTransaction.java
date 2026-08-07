package com.bank.loan.repayment.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 상환 거래. ERD STAGE 7 REPAYMENT_TRANSACTION 매핑.
 *
 * 1 회차당 1 SUCCESS row가 원칙. 실패·재시도 시 새 row 추가 (append-only — flows §2.2).
 * 역분개는 별도 row + reversal_yn=Y / reversal_target_rtx_id 참조 (본 단계 미구현).
 *
 * 본 단계 지원 시나리오: 회차 정확액 수동 상환 (rtx_type_cd=MANUAL, status=SUCCESS).
 * 자동이체·중도상환·부분상환은 후속.
 */
@Getter
@Entity
@Table(name = "repayment_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RepaymentTransaction extends BaseEntity {

    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_SUCCESS   = "SUCCESS";
    public static final String STATUS_FAILED    = "FAILED";

    public static final String TYPE_SCHEDULED   = "SCHEDULED";   // 예정 회차 정확액
    public static final String TYPE_PARTIAL     = "PARTIAL";     // 회차 부분상환
    public static final String TYPE_EARLY       = "EARLY";       // 중도상환
    public static final String TYPE_REVERSAL    = "REVERSAL";    // 역분개


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rtx_id")
    private Long rtxId;

    @Column(name = "cntr_id", nullable = false)
    private Long cntrId;

    @Column(name = "rsch_id")
    private Long rschId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "rtx_type_cd", nullable = false, length = 50)
    private String rtxTypeCd;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "principal_amount", nullable = false)
    private Long principalAmount;

    @Column(name = "interest_amount", nullable = false)
    private Long interestAmount;

    @Column(name = "overdue_interest_amount", nullable = false)
    private Long overdueInterestAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    @Column(name = "currency_cd", nullable = false, length = 10)
    private String currencyCd;

    @Column(name = "channel_cd", nullable = false, length = 50)
    private String channelCd;

    @Column(name = "rtx_status_cd", nullable = false, length = 50)
    private String rtxStatusCd;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "value_date", length = 8)
    private String valueDate;

    @Column(name = "balance_after")
    private Long balanceAfter;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "reversal_yn", nullable = false)
    private Boolean reversalYn;

    @Column(name = "reversal_target_rtx_id")
    private Long reversalTargetRtxId;

    /** payment-service 결제지시 ID. CLEARING 콜백 추적에 사용. */
    @Column(name = "pi_id", length = 100)
    private String piId;
}
