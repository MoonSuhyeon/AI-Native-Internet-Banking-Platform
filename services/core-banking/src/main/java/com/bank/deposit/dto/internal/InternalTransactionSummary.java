package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.Transaction;

import java.time.OffsetDateTime;

/**
 * 상담·조사에 필요한 만큼만 담은 거래 요약.
 *
 * <p>상대 계좌·예금주명은 넣지 않는다. 거래 상대는 <b>제3자</b>이고, 이 조회의 사유는
 * 대상 고객에 대한 것이지 상대방에 대한 것이 아니다. 필요해지면 별도 자원·사유로 연다.
 */
public record InternalTransactionSummary(
        String transactionNumber,
        Long accountId,
        String transactionType,
        String directionType,
        Long amount,
        Long balanceAfter,
        String status,
        String memo,
        OffsetDateTime transactionAt
) {
    public static InternalTransactionSummary from(Transaction t) {
        return new InternalTransactionSummary(
                t.getTransactionNumber(),
                t.getAccountId(),
                t.getTransactionType() != null ? t.getTransactionType().name() : null,
                t.getDirectionType() != null ? t.getDirectionType().name() : null,
                t.getAmount(),
                t.getBalanceAfter(),
                t.getStatus() != null ? t.getStatus().name() : null,
                t.getTransactionMemo(),
                t.getTransactionAt()
        );
    }
}
