package com.bank.deposit.dto;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.entity.TransactionCertificate;
import com.bank.deposit.domain.enums.CertificateType;

import java.time.OffsetDateTime;

/**
 * 발급된 증빙.
 *
 * <p>거래를 그대로 돌려주지 않고 <b>증빙에 실릴 항목만</b> 담는다. 거래 엔티티에는
 * 단말 ID·IP·멱등키처럼 문서에 인쇄될 이유가 없는 값이 있고, 그것까지 내보내면
 * 증빙 조회가 거래 원본 조회가 된다.
 *
 * <p>이체확인증에만 상대 정보가 있다. 영수증은 내 계좌에서 무슨 일이 있었나를
 * 말하므로 상대가 비어 있어도 문서가 성립한다.
 */
public record CertificateResponse(
        String certificateNo,
        CertificateType certificateType,
        OffsetDateTime issuedAt,

        String transactionNumber,
        OffsetDateTime transactionAt,
        String transactionType,
        Long amount,
        Long feeAmount,
        Long balanceAfter,
        String transactionMemo,

        String counterpartyBankName,
        String counterpartyAccountNo,
        String counterpartyName
) {
    public static CertificateResponse of(TransactionCertificate cert, Transaction tx) {
        boolean transferDoc = cert.getCertificateType() == CertificateType.TRANSFER_CONFIRMATION;
        return new CertificateResponse(
                cert.getCertificateNo(),
                cert.getCertificateType(),
                cert.getIssuedAt(),
                tx.getTransactionNumber(),
                tx.getTransactionAt(),
                tx.getTransactionType() == null ? null : tx.getTransactionType().name(),
                tx.getAmount(),
                tx.getFeeAmount(),
                tx.getBalanceAfter(),
                tx.getTransactionMemo(),
                transferDoc ? tx.getCounterpartyBankName() : null,
                transferDoc ? tx.getCounterpartyAccountNo() : null,
                transferDoc ? tx.getCounterpartyName() : null);
    }
}
