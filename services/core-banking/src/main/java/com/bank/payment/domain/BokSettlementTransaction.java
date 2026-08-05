package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BOK 정산거래 (bok_settlement_transaction)
 *
 * 거액이체 정산 추적 엔티티. PI 1:1.
 * - V11__bok_settlement_transaction.sql 정합
 * - 컬럼명세서 v12.2 기반
 * - settlement_requested_at / ack_received_at / settled_at: VARCHAR14 (yyyyMMddHHmmss)
 * - settlement_date: VARCHAR8 (yyyyMMdd)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BokSettlementTransaction {

    // 정산거래번호 (BST-yyyyMMdd-nnnnnn)
    private String settlementTransactionId;

    // 결제지시번호(자행)
    private String ourPaymentInstructionId;

    // 방향 (OUT=타행송신, IN=타행수신)
    private String direction;

    // 상대방거래참조 (IN 방향 시 상대 은행 거래 ID)
    private String counterpartyPaymentId;

    // BOK 정산 식별번호 (BOK-yyyyMMdd-nnnnnn, 우리 채번)
    private String bokReferenceNo;

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

    // 정산금액
    private BigDecimal settlementAmount;

    // 통화
    private String currency;

    // 정산상태 (REQUESTED/ACK_RECEIVED/SETTLED/REJECTED/TIMEOUT)
    private String settlementStatus;

    // 거절코드
    private String rejectCode;

    // 거절메시지
    private String rejectMessage;

    // 정산요청시각 (yyyyMMddHHmmss, VARCHAR14)
    private String settlementRequestedAt;

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
     * 거액송신 REQUESTED 정산거래 생성 (S3 송신 시 txStep4InterBok에서 호출).
     * direction=OUT, settlement_status=REQUESTED, currency=KRW, network=BOK_CLEARING.
     * sender_bank_clearing_id = settlementTransactionId (우리 채번 그대로 박제).
     * receiver_bank_clearing_id / counterparty_payment_id = NULL (IN 방향 시 채움).
     */
    public static BokSettlementTransaction requestedOut(
            String settlementTransactionId,
            String ourPaymentInstructionId,
            String bokReferenceNo,
            String senderBankCode,
            String senderAccountNoSnap,
            String senderHolderNameSnap,
            String receiverBankCode,
            String receiverAccountNoSnap,
            String receiverHolderNameSnap,
            BigDecimal settlementAmount,
            String settlementRequestedAt) {
        return BokSettlementTransaction.builder()
                .settlementTransactionId(settlementTransactionId)
                .ourPaymentInstructionId(ourPaymentInstructionId)
                .direction("OUT")
                .bokReferenceNo(bokReferenceNo)
                .senderBankClearingId(settlementTransactionId)
                .senderBankCode(senderBankCode)
                .senderAccountNoSnap(senderAccountNoSnap)
                .senderHolderNameSnap(senderHolderNameSnap)
                .receiverBankCode(receiverBankCode)
                .receiverAccountNoSnap(receiverAccountNoSnap)
                .receiverHolderNameSnap(receiverHolderNameSnap)
                .settlementAmount(settlementAmount)
                .currency("KRW")
                .settlementStatus("REQUESTED")
                .settlementRequestedAt(settlementRequestedAt)
                .network("BOK_CLEARING")
                .inquiryCount(0)
                .build();
    }
}
