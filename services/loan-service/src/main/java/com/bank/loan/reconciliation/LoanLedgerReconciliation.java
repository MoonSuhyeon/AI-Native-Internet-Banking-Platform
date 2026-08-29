package com.bank.loan.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 여신 보조부 ↔ 원장 대사 결과.
 *
 * <p>판정만이 아니라 양쪽 값을 함께 남긴다. 불일치를 나중에 볼 때 "얼마가 어긋났나"
 * 가 아니라 "어느 쪽이 무엇이라고 말했나" 를 알아야 원인을 좁힐 수 있다.
 */
@Entity
@Table(name = "loan_ledger_reconciliation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanLedgerReconciliation {

    public static final String RESULT_MATCH = "MATCH";
    public static final String RESULT_MISMATCH = "MISMATCH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recon_id")
    private Long reconId;

    @Column(name = "base_date", nullable = false, length = 8, unique = true)
    private String baseDate;

    @Column(name = "subledger_disbursed_amount", nullable = false)
    private Long subledgerDisbursedAmount;

    @Column(name = "subledger_disbursed_count", nullable = false)
    private Integer subledgerDisbursedCount;

    @Column(name = "subledger_repaid_amount", nullable = false)
    private Long subledgerRepaidAmount;

    @Column(name = "subledger_repaid_count", nullable = false)
    private Integer subledgerRepaidCount;

    @Column(name = "ledger_disbursed_amount", nullable = false)
    private Long ledgerDisbursedAmount;

    @Column(name = "ledger_disbursed_count", nullable = false)
    private Integer ledgerDisbursedCount;

    @Column(name = "ledger_repaid_amount", nullable = false)
    private Long ledgerRepaidAmount;

    @Column(name = "ledger_repaid_count", nullable = false)
    private Integer ledgerRepaidCount;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "break_detail", length = 500)
    private String breakDetail;

    @Column(name = "reconciled_at", nullable = false)
    private OffsetDateTime reconciledAt;

    @Builder
    private LoanLedgerReconciliation(String baseDate,
                                     Long subledgerDisbursedAmount, Integer subledgerDisbursedCount,
                                     Long subledgerRepaidAmount, Integer subledgerRepaidCount,
                                     Long ledgerDisbursedAmount, Integer ledgerDisbursedCount,
                                     Long ledgerRepaidAmount, Integer ledgerRepaidCount,
                                     String result, String breakDetail) {
        this.baseDate = baseDate;
        this.subledgerDisbursedAmount = subledgerDisbursedAmount;
        this.subledgerDisbursedCount = subledgerDisbursedCount;
        this.subledgerRepaidAmount = subledgerRepaidAmount;
        this.subledgerRepaidCount = subledgerRepaidCount;
        this.ledgerDisbursedAmount = ledgerDisbursedAmount;
        this.ledgerDisbursedCount = ledgerDisbursedCount;
        this.ledgerRepaidAmount = ledgerRepaidAmount;
        this.ledgerRepaidCount = ledgerRepaidCount;
        this.result = result;
        this.breakDetail = breakDetail;
        this.reconciledAt = OffsetDateTime.now();
    }

    public boolean isMismatch() {
        return RESULT_MISMATCH.equals(result);
    }

    /** 같은 날 재실행하면 갱신한다. 하루에 한 행이다. */
    public void replaceWith(LoanLedgerReconciliation fresh) {
        this.subledgerDisbursedAmount = fresh.subledgerDisbursedAmount;
        this.subledgerDisbursedCount = fresh.subledgerDisbursedCount;
        this.subledgerRepaidAmount = fresh.subledgerRepaidAmount;
        this.subledgerRepaidCount = fresh.subledgerRepaidCount;
        this.ledgerDisbursedAmount = fresh.ledgerDisbursedAmount;
        this.ledgerDisbursedCount = fresh.ledgerDisbursedCount;
        this.ledgerRepaidAmount = fresh.ledgerRepaidAmount;
        this.ledgerRepaidCount = fresh.ledgerRepaidCount;
        this.result = fresh.result;
        this.breakDetail = fresh.breakDetail;
        this.reconciledAt = OffsetDateTime.now();
    }
}
