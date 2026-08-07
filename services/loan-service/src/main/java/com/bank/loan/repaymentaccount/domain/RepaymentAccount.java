package com.bank.loan.repaymentaccount.domain;

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
 * 상환계좌. ERD STAGE 6 REPAYMENT_ACCOUNT 매핑.
 *
 * 한 약정에 1 row (cntr_id UNIQUE).
 * 상태 전이:
 *   REGISTERED → VERIFIED (외부 계좌검증 성공)
 *
 * VERIFIED 가 되어야 자금 인출(drawdown) · 자동이체 가능 — flows §1.1 사전조건.
 */
@Getter
@Entity
@Table(name = "repayment_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RepaymentAccount extends BaseEntity {

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_VERIFIED   = "VERIFIED";


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "racct_id")
    private Long racctId;

    @Column(name = "cntr_id", nullable = false, unique = true)
    private Long cntrId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "account_no_masked", length = 50)
    private String accountNoMasked;

    @Column(name = "account_no_enc")
    private byte[] accountNoEnc;

    @Column(name = "bank_cd", nullable = false, length = 10)
    private String bankCd;

    @Column(name = "holder_name_masked", length = 50)
    private String holderNameMasked;

    @Column(name = "holder_name_enc")
    private byte[] holderNameEnc;

    @Column(name = "racct_status_cd", nullable = false, length = 50)
    private String racctStatusCd;

    @Column(name = "auto_debit_yn", nullable = false)
    private Boolean autoDebitYn;

    @Column(name = "debit_day")
    private Integer debitDay;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    public boolean isVerified() {
        return STATUS_VERIFIED.equals(racctStatusCd);
    }

    public String currentStatus() {
        return racctStatusCd;
    }

    /** REGISTERED → VERIFIED 전이. 다른 상태에서는 호출자에서 차단. */
    public void markVerified(OffsetDateTime at) {
        this.racctStatusCd = STATUS_VERIFIED;
        this.verifiedAt = at;
    }
}
