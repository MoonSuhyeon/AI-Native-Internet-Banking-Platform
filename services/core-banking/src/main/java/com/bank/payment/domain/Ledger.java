package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계좌원장 (ledger)
 *
 * 거래분개 (복식부기). 차변/대변 분개 단위.
 * - V3__create_ledger.sql 정합
 * - 컬럼명세서 v12.2 기반
 * - payment_instruction FK (NULL 허용, 이자/수기분개 시 NULL)
 * - original_ledger_id self FK (역분개 시 원분개 참조)
 * - 비즈니스 메서드는 Stage 5에서 추가
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Ledger {

    // 분개번호
    private String ledgerId;

    // 결제지시번호
    private String paymentInstructionId;

    // 계좌번호
    private String accountId;

    // 원분개참조
    private String originalLedgerId;

    // 회계번호
    private String journalNo;

    // 계좌번호_스냅샷
    private String accountNoSnap;

    // 예금주명_스냅샷
    private String holderNameSnap;

    // 차변대변구분
    private String debitCredit;

    // 분개종류
    private String journalType;

    // 금액
    private BigDecimal amount;

    // 통화
    private String currency;

    // 분개직전잔액
    private BigDecimal balanceBefore;

    // 분개직후잔액
    private BigDecimal balanceAfter;

    // 상대계좌번호_스냅샷
    private String counterpartyAccountNoSnap;

    // 상대은행코드_스냅샷
    private String counterpartyBankCodeSnap;

    // 상대예금주명_스냅샷
    private String counterpartyHolderNameSnap;

    // 거래일자
    private String transactionDate;

    // 기장일자
    private String postingDate;

    // 자금가용일
    private String valueDate;

    // 기장시각
    private LocalDateTime postedAt;

    // 시스템적요
    private String systemDescription;

    // 통장에찍히는메모_스냅샷
    private String passbookMemoSnap;

    // 역분개여부
    private Boolean isReversal;

    // 역분개사유
    private String reversalReason;

    // 기장상태
    private String postingStatus;

    // 최초등록일시
    private LocalDateTime firstRegisteredAt;

    // 최초등록자식별번호
    private String firstRegistrantId;

    // 최종수정일시
    private LocalDateTime lastModifiedAt;

    // 최종수정자식별번호
    private String lastModifierId;

    // ── 자행 출금 분개 [자행] ────────────────────────────
    /** 자행이체 출금 분개. 송신계좌 DEBIT TRANSFER_OUT. (P-014, 정책 v7.2 §2) */
    public static Ledger intraTransferOut(
            String ledgerId, String paymentInstructionId, String accountId,
            String journalNo, String accountNoSnap, String holderNameSnap,
            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("DEBIT")
                .journalType("TRANSFER_OUT")
                .amount(amount)
                .currency(currency)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 자행 입금 분개 [자행] ────────────────────────────
    /** 자행이체 입금 분개. 수신계좌 CREDIT TRANSFER_IN. (P-014, 정책 v7.2 §2) */
    public static Ledger intraTransferIn(
            String ledgerId, String paymentInstructionId, String accountId,
            String journalNo, String accountNoSnap, String holderNameSnap,
            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("CREDIT")
                .journalType("TRANSFER_IN")
                .amount(amount)
                .currency(currency)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 타행 수신 입금 분개 [타행 IN JN-01] ─────────────────
    /** 타행이체 수신 입금 분개. 수신계좌 CREDIT TRANSFER_IN + 상대방(송신) 정보 세팅. (P-014 IN방향) */
    public static Ledger interTransferIn(
            String ledgerId, String paymentInstructionId, String accountId,
            String journalNo, String accountNoSnap, String holderNameSnap,
            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription,
            String counterpartySenderAccountNo, String counterpartySenderBankCode,
            String counterpartySenderHolderName) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("CREDIT")
                .journalType("TRANSFER_IN")
                .amount(amount)
                .currency(currency)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .counterpartyAccountNoSnap(counterpartySenderAccountNo)
                .counterpartyBankCodeSnap(counterpartySenderBankCode)
                .counterpartyHolderNameSnap(counterpartySenderHolderName)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 타행 출금 분개 [타행 JN-01 차변] ─────────────────
    /** 타행이체 출금 분개. 송신계좌 DEBIT TRANSFER_OUT. (P-014 시트2 JN-01) */
    public static Ledger interTransferOut(
            String ledgerId, String paymentInstructionId, String accountId,
            String journalNo, String accountNoSnap, String holderNameSnap,
            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("DEBIT")
                .journalType("TRANSFER_OUT")
                .amount(amount)
                .currency(currency)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 타행 청산대기 분개 [타행 JN-01 대변] ─────────────
    /**
     * 타행이체 청산대기 분개. KB-CLR-088 CREDIT CLEARING_PENDING. (P-014 시트2 JN-01)
     * 내부계정 잔액은 결제계가 추적하지 않으므로 balance=0,0 처리.
     */
    public static Ledger clearingPending(
            String ledgerId, String paymentInstructionId,
            String journalNo, BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId("KB-CLR-088")
                .journalNo(journalNo)
                .accountNoSnap("KB-CLR-088")
                .holderNameSnap("KB청산대기(신한)")
                .debitCredit("CREDIT")
                .journalType("CLEARING_PENDING")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── BOK 청산대기 분개 [BOK JN-01 대변] ──────────────
    /**
     * BOK 거액이체 청산대기 분개. KB-CLR-BOK CREDIT CLEARING_PENDING. (P-014 시트2 JN-01)
     * 내부계정 잔액은 결제계가 추적하지 않으므로 balance=0,0 처리.
     */
    public static Ledger clearingPendingBok(
            String ledgerId, String paymentInstructionId,
            String journalNo, BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId("KB-CLR-BOK")
                .journalNo(journalNo)
                .accountNoSnap("KB-CLR-BOK")
                .holderNameSnap("KB청산대기(한은)")
                .debitCredit("CREDIT")
                .journalType("CLEARING_PENDING")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 타행 수수료 분개 [타행 JN-02 차변] ───────────────
    /**
     * 타행이체 수수료 분개. 송신계좌 DEBIT FEE. (P-014 시트2 JN-02)
     * 수수료는 별도 deposit API 호출 없이 분개만 기록 → balance=0,0 처리.
     */
    public static Ledger fee(
            String ledgerId, String paymentInstructionId, String accountId,
            String journalNo, String accountNoSnap, String holderNameSnap,
            BigDecimal feeAmount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("DEBIT")
                .journalType("FEE")
                .amount(feeAmount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 타행 수수료수익 분개 [타행 JN-02 대변] ───────────
    /**
     * 타행이체 수수료수익 분개. KB-FEE-001 CREDIT FEE_INCOME. (P-014 시트2 JN-02)
     * 내부계정 잔액은 결제계가 추적하지 않으므로 balance=0,0 처리.
     */
    public static Ledger feeIncome(
            String ledgerId, String paymentInstructionId,
            String journalNo, BigDecimal feeAmount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId("KB-FEE-001")
                .journalNo(journalNo)
                .accountNoSnap("KB-FEE-001")
                .holderNameSnap("KB법인")
                .debitCredit("CREDIT")
                .journalType("FEE_INCOME")
                .amount(feeAmount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 역분개: 송신계좌 출금취소 [JN-1역 대변] ──────────
    /**
     * 타행/BOK 거절 역분개 — 송신계좌 CREDIT REVERSAL_TRANSFER_OUT. (F2/F3 보상, P-014 JN-1역)
     * balance_before/after: B-5 출금취소 응답잔액 박제. is_reversal=TRUE, reversalReason 파라미터.
     * chk_ledger_reversal_original_consistency: original_ledger_id NOT NULL 필수.
     */
    public static Ledger reversalTransferOut(
            String ledgerId, String paymentInstructionId, String accountId,
            String originalLedgerId, String journalNo,
            String accountNoSnap, String holderNameSnap,
            BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription, String reversalReason) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .originalLedgerId(originalLedgerId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("CREDIT")
                .journalType("REVERSAL_TRANSFER_OUT")
                .amount(amount)
                .currency(currency)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(true)
                .reversalReason(reversalReason)
                .postingStatus("POSTED")
                .build();
    }

    // ── 역분개: 청산대기 취소 [JN-1역 차변] ──────────────
    /**
     * 타행/BOK 거절 역분개 — KB-CLR-0xx DEBIT REVERSAL_CLEARING_PENDING. (F2/F3 보상, P-014 JN-1역)
     * accountId/accountNoSnap/holderNameSnap: 원분개 CLEARING_PENDING에서 계승. balance=0,0.
     */
    public static Ledger reversalClearingPending(
            String ledgerId, String paymentInstructionId,
            String originalLedgerId, String journalNo,
            String accountId, String accountNoSnap, String holderNameSnap,
            BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription, String reversalReason) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .originalLedgerId(originalLedgerId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("DEBIT")
                .journalType("REVERSAL_CLEARING_PENDING")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(true)
                .reversalReason(reversalReason)
                .postingStatus("POSTED")
                .build();
    }

    // ── 역분개: 수수료 취소 [JN-2역 대변] ────────────────
    /**
     * 타행/BOK 거절 역분개 — 송신계좌 CREDIT REVERSAL_FEE. (F2/F3 보상, P-014 JN-2역)
     * balance=0,0 (원 FEE 분개도 별도 deposit 호출 없이 0,0이므로 대칭).
     */
    public static Ledger reversalFee(
            String ledgerId, String paymentInstructionId, String accountId,
            String originalLedgerId, String journalNo,
            String accountNoSnap, String holderNameSnap,
            BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription, String reversalReason) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .originalLedgerId(originalLedgerId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("CREDIT")
                .journalType("REVERSAL_FEE")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(true)
                .reversalReason(reversalReason)
                .postingStatus("POSTED")
                .build();
    }

    // ── 역분개: 수수료수익 취소 [JN-2역 차변] ────────────
    /**
     * 타행/BOK 거절 역분개 — KB-FEE-001 DEBIT REVERSAL_FEE_INCOME. (F2/F3 보상, P-014 JN-2역)
     * balance=0,0 (원 FEE_INCOME도 내부계정 0,0이므로 대칭).
     */
    public static Ledger reversalFeeIncome(
            String ledgerId, String paymentInstructionId,
            String originalLedgerId, String journalNo,
            BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription, String reversalReason) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId("KB-FEE-001")
                .originalLedgerId(originalLedgerId)
                .journalNo(journalNo)
                .accountNoSnap("KB-FEE-001")
                .holderNameSnap("KB법인")
                .debitCredit("DEBIT")
                .journalType("REVERSAL_FEE_INCOME")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(true)
                .reversalReason(reversalReason)
                .postingStatus("POSTED")
                .build();
    }

    // ── 정산 분개: 청산대기 해소 [정산 JN 차변] ──────────
    /**
     * 청산대기 계정 해소 분개. DEBIT CLEARING_PENDING_UNWIND. (KFTC 마감정산 / BOK RTGS 정산)
     * accountId/accountNoSnap/holderNameSnap: 원분개 CLEARING_PENDING에서 계승.
     *   KFTC → "KB-CLR-088" / "KB청산대기(신한)"
     *   BOK  → "KB-CLR-BOK" / "KB청산대기(한은)"
     * isReversal=false — 역분개가 아닌 정산 시점의 신규 회계 사건.
     * balance=0,0 (내부계정, 결제계 미추적).
     */
    public static Ledger clearingPendingUnwind(
            String ledgerId, String paymentInstructionId,
            String accountId, String accountNoSnap, String holderNameSnap,
            String journalNo, BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId(accountId)
                .journalNo(journalNo)
                .accountNoSnap(accountNoSnap)
                .holderNameSnap(holderNameSnap)
                .debitCredit("DEBIT")
                .journalType("CLEARING_PENDING_UNWIND")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }

    // ── 정산 분개: 한은당좌 정산 [정산 JN 대변] ──────────
    /**
     * 한은당좌 정산 분개. KB-DDA CREDIT INTERBANK_SETTLEMENT. (KFTC 마감정산 / BOK RTGS 정산)
     * clearingPendingUnwind의 차대변 상대편.
     * isReversal=false — 역분개가 아닌 정산 시점의 신규 회계 사건.
     * balance=0,0 (내부계정, 결제계 미추적).
     */
    public static Ledger bokDda(
            String ledgerId, String paymentInstructionId,
            String journalNo, BigDecimal amount,
            String currency, String transactionDate, String postingDate, String valueDate,
            LocalDateTime postedAt, String systemDescription) {
        return Ledger.builder()
                .ledgerId(ledgerId)
                .paymentInstructionId(paymentInstructionId)
                .accountId("KB-DDA")
                .journalNo(journalNo)
                .accountNoSnap("KB-DDA")
                .holderNameSnap("KB한은당좌")
                .debitCredit("CREDIT")
                .journalType("INTERBANK_SETTLEMENT")
                .amount(amount)
                .currency(currency)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .transactionDate(transactionDate)
                .postingDate(postingDate)
                .valueDate(valueDate)
                .postedAt(postedAt)
                .systemDescription(systemDescription)
                .isReversal(false)
                .postingStatus("POSTED")
                .build();
    }
}
