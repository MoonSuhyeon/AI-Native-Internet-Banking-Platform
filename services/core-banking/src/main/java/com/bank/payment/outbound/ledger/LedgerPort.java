package com.bank.payment.outbound.ledger;

import com.bank.payment.outbound.ledger.dto.BalanceInquiryData;
import com.bank.payment.outbound.ledger.dto.BalanceTxData;
import com.bank.payment.outbound.ledger.dto.DepositRequest;
import com.bank.payment.outbound.ledger.dto.DepositResponse;
import com.bank.payment.outbound.ledger.dto.LimitInquiryData;
import com.bank.payment.outbound.ledger.dto.WithdrawCancelData;
import com.bank.payment.outbound.ledger.dto.WithdrawRequest;

/**
 * 원장(입출금) 포트.
 *
 * <p>병합 전에는 deposit-service 를 향한 {@code @FeignClient} 였다. HTTP 호출이었기 때문에
 * 출금과 입금이 각각 별개의 원격 트랜잭션이었고, 그 사이 실패를 {@link #withdrawCancel}
 * 보상으로 되돌려야 했다. 이제 같은 프로세스·같은 DataSource 라 호출자의 트랜잭션에
 * 참여한다.
 *
 * <p>다만 {@link #withdrawCancel} 은 남긴다 — 타행이체는 상대 은행 응답을 기다리는 동안
 * 우리 쪽 출금이 이미 커밋돼 있어야 하므로, 여전히 보상이 유일한 되돌리기 수단이다.
 */
public interface LedgerPort {

    DepositResponse<BalanceInquiryData> getBalance(String accountNo);

    DepositResponse<LimitInquiryData> getLimit(String accountNo, String date);

    BalanceTxData withdraw(String idempotencyKey, WithdrawRequest request);

    BalanceTxData deposit(String idempotencyKey, DepositRequest request);

    /** 출금 취소(보상). 타행이체 실패 경로에서만 쓴다. */
    WithdrawCancelData withdrawCancel(String idempotencyKey, Long transactionId);
}
