package com.bank.loan.schedule.domain;

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

/**
 * 상환 스케줄 (회차별). ERD STAGE 7 REPAYMENT_SCHEDULE 매핑.
 *
 * 한 약정에 N 행(회차수만큼). 금리 변경 시 새 rsch_version_cd 로 재생성,
 * 구버전 행은 SUPERSEDED 로 전이 (행 삭제 금지 — flows §2.3 append-only).
 *
 * 상태 전이:
 *   DUE → PAID        (회차 정상 납부)
 *   DUE → OVERDUE     (due_date 경과 미납)
 *   DUE → SUPERSEDED  (금리 변경으로 신규 버전 생성됨)
 *   OVERDUE → PAID    (연체 해소 시)
 */
@Getter
@Entity
@Table(name = "repayment_schedule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RepaymentSchedule extends BaseEntity {

    public static final String STATUS_DUE          = "DUE";
    public static final String STATUS_PAID         = "PAID";
    public static final String STATUS_OVERDUE      = "OVERDUE";
    public static final String STATUS_SUPERSEDED   = "SUPERSEDED";
    public static final String STATUS_PARTIAL_PAID = "PARTIAL_PAID";

    public static final String VERSION_INITIAL = "V1";


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rsch_id")
    private Long rschId;

    @Column(name = "cntr_id", nullable = false)
    private Long cntrId;

    @Column(name = "installment_no", nullable = false)
    private Integer installmentNo;

    @Column(name = "due_date", nullable = false, length = 8)
    private String dueDate;

    @Column(name = "scheduled_principal", nullable = false)
    private Long scheduledPrincipal;

    @Column(name = "scheduled_interest", nullable = false)
    private Long scheduledInterest;

    @Column(name = "scheduled_total", nullable = false)
    private Long scheduledTotal;

    @Column(name = "remaining_balance", nullable = false)
    private Long remainingBalance;

    @Column(name = "applied_rate_bps", nullable = false)
    private Integer appliedRateBps;

    @Column(name = "rsch_status_cd", nullable = false, length = 50)
    private String rschStatusCd;

    @Column(name = "rsch_version_cd", nullable = false, length = 50)
    private String rschVersionCd;

    /** 'Y' 면 원 due_date 가 비영업일이라 다음 영업일로 이동된 회차. 미보정/구약정은 'N'. */
    @Column(name = "holiday_adjusted_yn", nullable = false)
    private Boolean holidayAdjustedYn;

    public boolean isHolidayAdjusted() {
        return Boolean.TRUE.equals(holidayAdjustedYn);
    }

    public boolean isPayable() {
        return STATUS_DUE.equals(rschStatusCd) || STATUS_OVERDUE.equals(rschStatusCd);
    }

    public boolean isPaid() {
        return STATUS_PAID.equals(rschStatusCd);
    }

    public String currentStatus() {
        return rschStatusCd;
    }

    /** DUE 또는 OVERDUE 회차를 PAID 로 전이. 다른 상태에서는 호출자에서 차단. */
    public void markPaid() {
        this.rschStatusCd = STATUS_PAID;
    }

    /** DUE 회차를 OVERDUE 로 전이 (연체 rollover 배치). 다른 상태에서는 호출자에서 차단. */
    public void markOverdue() {
        this.rschStatusCd = STATUS_OVERDUE;
    }

    /** PAID 회차를 DUE 로 되돌림 (역분개). 다른 상태에서는 호출자에서 차단. */
    public void markDue() {
        this.rschStatusCd = STATUS_DUE;
    }

    /** 부분상환으로 회차가 일부 납부된 상태. DUE/OVERDUE → PARTIAL_PAID. 다른 상태는 호출자에서 차단. */
    public void markPartialPaid() {
        this.rschStatusCd = STATUS_PARTIAL_PAID;
    }

    /** 부분상환 추가 호출 가능 여부 — DUE / OVERDUE / PARTIAL_PAID 에서만 허용. */
    public boolean isPartialPayable() {
        return STATUS_DUE.equals(rschStatusCd)
                || STATUS_OVERDUE.equals(rschStatusCd)
                || STATUS_PARTIAL_PAID.equals(rschStatusCd);
    }

    public boolean isOverdue() {
        return STATUS_OVERDUE.equals(rschStatusCd);
    }

    /** 금리 변경 등으로 새 버전이 생성될 때 기존 행을 비활성화 (append-only — 행 삭제 금지). */
    public void markSuperseded() {
        this.rschStatusCd = STATUS_SUPERSEDED;
    }
}
