package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.Transaction;

import java.time.OffsetDateTime;

/**
 * 상담·조사에 필요한 만큼만 담은 거래 요약.
 *
 * <p>상대 계좌·예금주명은 넣지 않는다. 거래 상대는 <b>제3자</b>이고, 이 조회의 사유는
 * 대상 고객에 대한 것이지 상대방에 대한 것이 아니다. 필요해지면 별도 자원·사유로 연다.
 *
 * <p><b>{@code internalTransfer} 는 왜 있나.</b> 현금흐름을 집계할 때 본인 계좌 사이의
 * 이체는 수입도 지출도 아니다. 이를 가려내려면 상대 계좌를 알아야 하지만, 상대 계좌를
 * 그대로 주면 위에서 감춘 제3자가 도로 드러난다. 그래서 <b>상대가 이 고객 본인의
 * 계좌인지</b>라는 참·거짓 하나만 서버가 계산해 준다. 부르는 쪽이 알아야 할 것은
 * 그것뿐이고, 그 이상은 나가지 않는다.
 */
public record InternalTransactionSummary(
        Long transactionId,
        String transactionNumber,
        Long accountId,
        String transactionType,
        String directionType,
        Long amount,
        Long balanceAfter,
        String status,
        String memo,
        OffsetDateTime transactionAt,
        /** 상대 계좌가 이 고객 본인의 계좌인지. 현금흐름 집계에서 제외 판단에 쓴다. */
        boolean internalTransfer
) {
    public static InternalTransactionSummary from(Transaction t, java.util.Set<Long> ownAccountIds) {
        return new InternalTransactionSummary(
                t.getTransactionId(),
                t.getTransactionNumber(),
                t.getAccountId(),
                t.getTransactionType() != null ? t.getTransactionType().name() : null,
                t.getDirectionType() != null ? t.getDirectionType().name() : null,
                t.getAmount(),
                t.getBalanceAfter(),
                t.getStatus() != null ? t.getStatus().name() : null,
                t.getTransactionMemo(),
                t.getTransactionAt(),
                t.getCounterpartyAccountId() != null
                        && ownAccountIds.contains(t.getCounterpartyAccountId())
        );
    }
}
