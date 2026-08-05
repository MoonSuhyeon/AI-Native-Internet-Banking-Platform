package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KFTC 청산거래 (kftc_clearing_transaction)
 *
 * 타행이체 청산 추적 엔티티. PI 1:1.
 * - V10__kftc_clearing_transaction.sql 정합
 * - 컬럼명세서 v12.2 기반
 * - clearing_requested_at / ack_received_at / settled_at: VARCHAR14 (yyyyMMddHHmmss)
 * - settlement_date: VARCHAR8 (yyyyMMdd)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KftcClearingTransaction {

    // 청산거래번호 (KCT-yyyyMMdd-nnnnnn)
    private String clearingTransactionId;

    // 결제지시번호(자행)
    private String ourPaymentInstructionId;

    // 방향 (OUT=타행송신, IN=타행수신)
    private String direction;

    // 상대방거래참조 (IN 방향 시 상대 은행 거래 ID)
    private String counterpartyPaymentId;

    // KFTC 청산식별번호 (KFTC-yyyyMMdd-nnnnnn, 우리 채번)
    private String clearingNo;

    // 송신은행청산ID (우리 채번 박제)
    private String senderBankClearingId;

    // 수신은행청산ID (SETTLEMENT 시 상대 은행 발급)
    private String receiverBankClearingId;

    // 송신은행코드
    private String senderBankCode;

    // 송신계좌번호_스냅샷
    private String senderAccountNoSnap;

    // 송신예금주명_스냅샷
    private String senderHolderNameSnap;

    // 수신은행코드
    private String receiverBankCode;

    // 수신계좌번호_스냅샷
    private String receiverAccountNoSnap;

    // 수신예금주명_스냅샷
    private String receiverHolderNameSnap;

    // 청산금액
    private BigDecimal clearingAmount;

    // 통화
    private String currency;

    // 청산상태 (REQUESTED/ACK/SETTLED/REJECTED/TIMEOUT)
    private String clearingStatus;

    // 거절코드
    private String rejectCode;

    // 거절메시지
    private String rejectMessage;

    // 청산요청시각 (yyyyMMddHHmmss, VARCHAR14)
    private String clearingRequestedAt;

    // ACK수신시각 (yyyyMMddHHmmss, VARCHAR14)
    private String ackReceivedAt;

    // 정산완료시각 (yyyyMMddHHmmss, VARCHAR14)
    private String settledAt;

    // 정산일자 (yyyyMMdd, VARCHAR8)
    private String settlementDate;

    // 청산망종류
    private String network;

    // 마지막조회시각
    private LocalDateTime lastInquiryAt;

    // 조회횟수
    private Integer inquiryCount;

    // 최초등록일시
    private LocalDateTime firstRegisteredAt;

    // 최초등록자식별번호
    private String firstRegistrantId;

    // 최종수정일시
    private LocalDateTime lastModifiedAt;

    // 최종수정자식별번호
    private String lastModifierId;

    /**
     * 타행송신 REQUESTED 청산거래 생성 (S2-A 송신 시 txStep4InterBank에서 호출).
     * direction=OUT, clearing_status=REQUESTED, currency=KRW, network=KFTC_CLEARING.
     * sender_bank_clearing_id = clearingTransactionId (우리 채번 그대로 박제).
     */
    public static KftcClearingTransaction requestedOut(
            String clearingTransactionId,
            String ourPaymentInstructionId,
            String clearingNo,
            String senderBankCode,
            String senderAccountNoSnap,
            String senderHolderNameSnap,
            String receiverBankCode,
            String receiverAccountNoSnap,
            String receiverHolderNameSnap,
            BigDecimal clearingAmount,
            String clearingRequestedAt) {
        return KftcClearingTransaction.builder()
                .clearingTransactionId(clearingTransactionId)
                .ourPaymentInstructionId(ourPaymentInstructionId)
                .direction("OUT")
                .clearingNo(clearingNo)
                .senderBankClearingId(clearingTransactionId)
                .senderBankCode(senderBankCode)
                .senderAccountNoSnap(senderAccountNoSnap)
                .senderHolderNameSnap(senderHolderNameSnap)
                .receiverBankCode(receiverBankCode)
                .receiverAccountNoSnap(receiverAccountNoSnap)
                .receiverHolderNameSnap(receiverHolderNameSnap)
                .clearingAmount(clearingAmount)
                .currency("KRW")
                .clearingStatus("REQUESTED")
                .clearingRequestedAt(clearingRequestedAt)
                .network("KFTC_CLEARING")
                .inquiryCount(0)
                .build();
    }

    /**
     * 타행수신 SETTLED 청산거래 생성 (IN-01 수신 입금 완료 시).
     * direction=IN, clearingStatus=SETTLED, receiverBankClearingId=우리채번, senderBankClearingId=null.
     */
    public static KftcClearingTransaction settledIn(
            String clearingTransactionId,
            String ourPaymentInstructionId,
            String counterpartyPaymentId,
            String clearingNo,
            String senderBankCode,
            String senderAccountNoSnap,
            String senderHolderNameSnap,
            String receiverBankCode,
            String receiverAccountNoSnap,
            String receiverHolderNameSnap,
            BigDecimal clearingAmount,
            String settledAt,
            String settlementDate) {
        return KftcClearingTransaction.builder()
                .clearingTransactionId(clearingTransactionId)
                .ourPaymentInstructionId(ourPaymentInstructionId)
                .direction("IN")
                .counterpartyPaymentId(counterpartyPaymentId)
                .clearingNo(clearingNo)
                .receiverBankClearingId(clearingTransactionId)
                .senderBankCode(senderBankCode)
                .senderAccountNoSnap(senderAccountNoSnap)
                .senderHolderNameSnap(senderHolderNameSnap)
                .receiverBankCode(receiverBankCode)
                .receiverAccountNoSnap(receiverAccountNoSnap)
                .receiverHolderNameSnap(receiverHolderNameSnap)
                .clearingAmount(clearingAmount)
                .currency("KRW")
                .clearingStatus("SETTLED")
                .clearingRequestedAt(settledAt)
                .settledAt(settledAt)
                .settlementDate(settlementDate)
                .network("KFTC_CLEARING")
                .inquiryCount(0)
                .build();
    }

    /**
     * 타행수신 REJECTED 청산거래 생성 (IN-03 수신 거절 시).
     * direction=IN, clearingStatus=REJECTED, settledAt/settlementDate=null.
     */
    public static KftcClearingTransaction rejectedIn(
            String clearingTransactionId,
            String ourPaymentInstructionId,
            String counterpartyPaymentId,
            String clearingNo,
            String senderBankCode,
            String senderAccountNoSnap,
            String senderHolderNameSnap,
            String receiverBankCode,
            String receiverAccountNoSnap,
            String receiverHolderNameSnap,
            BigDecimal clearingAmount,
            String rejectCode,
            String rejectMessage,
            String rejectedAt) {
        return KftcClearingTransaction.builder()
                .clearingTransactionId(clearingTransactionId)
                .ourPaymentInstructionId(ourPaymentInstructionId)
                .direction("IN")
                .counterpartyPaymentId(counterpartyPaymentId)
                .clearingNo(clearingNo)
                .receiverBankClearingId(clearingTransactionId)
                .senderBankCode(senderBankCode)
                .senderAccountNoSnap(senderAccountNoSnap)
                .senderHolderNameSnap(senderHolderNameSnap)
                .receiverBankCode(receiverBankCode)
                .receiverAccountNoSnap(receiverAccountNoSnap)
                .receiverHolderNameSnap(receiverHolderNameSnap)
                .clearingAmount(clearingAmount)
                .currency("KRW")
                .clearingStatus("REJECTED")
                .clearingRequestedAt(rejectedAt)
                .rejectCode(rejectCode)
                .rejectMessage(rejectMessage)
                .network("KFTC_CLEARING")
                .inquiryCount(0)
                .build();
    }
}
