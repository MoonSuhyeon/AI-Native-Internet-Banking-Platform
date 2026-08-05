package com.bank.payment.domain.mapper;

public interface IdSequenceMapper {
    // 각 테이블의 현재 MAX ID 조회 (IdGenerator 시드용)
    // 빈 테이블이면 null 반환 → IdGenerator에서 null 가드
    String selectMaxPaymentInstructionId();
    String selectMaxLedgerId();
    String selectMaxJournalNo();
    String selectMaxHistoryId();
    String selectMaxMessageId();
    String selectMaxCallId();
    String selectMaxClearingTransactionId();
    String selectMaxClearingNo();
    String selectMaxSettlementTransactionId();
    String selectMaxBokReferenceNo();
}
