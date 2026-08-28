package com.bank.payment.outbound.ledger;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.service.DepositV1Service;
import com.bank.deposit.service.TransactionService;
import com.bank.payment.outbound.ledger.dto.BalanceInquiryData;
import com.bank.payment.outbound.ledger.dto.BalanceTxData;
import com.bank.payment.outbound.ledger.dto.DepositRequest;
import com.bank.payment.outbound.ledger.dto.DepositResponse;
import com.bank.payment.outbound.ledger.dto.LimitInquiryData;
import com.bank.payment.outbound.ledger.dto.WithdrawCancelData;
import com.bank.payment.outbound.ledger.dto.WithdrawRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 원장 포트의 인프로세스 구현.
 *
 * <p>핵심 변화는 트랜잭션이다. HTTP 시절에는 출금과 입금이 각각 원격 트랜잭션이라
 * 그 사이 실패를 보상으로 되돌려야 했다. 이제 호출자가 {@code @Transactional} 안에 있으면
 * 두 호출이 같은 트랜잭션에서 일어나고, 실패하면 롤백이 알아서 되돌린다.
 *
 * <p>자행이체는 이 성질을 쓰고, 타행이체는 쓰지 않는다 — 상대 은행에 넘기기 전에
 * 우리 쪽 출금이 커밋돼 있어야 하기 때문이다. 그래서 보상 경로도 남아 있다.
 */
@Component
@RequiredArgsConstructor
public class LocalLedgerAdapter implements LedgerPort {

    /** deposit 은 성공 코드를 DEP-0000 으로 쓴다. HTTP 응답 형태를 그대로 유지한다. */
    private static final String SUCCESS_CODE = "DEP-0000";

    private final TransactionService transactionService;
    private final DepositV1Service depositV1Service;

    @Override
    public DepositResponse<BalanceInquiryData> getBalance(String accountNo) {
        var b = depositV1Service.getBalance(accountNo);
        return ok(new BalanceInquiryData(
                b.accountNo(), b.balance(), b.availableBalance(), b.holdAmount(),
                b.currency(), b.lastTxAt(), (int) b.version()));
    }

    @Override
    public DepositResponse<LimitInquiryData> getLimit(String accountNo, String date) {
        var l = depositV1Service.getLimit(accountNo, date);
        return ok(new LimitInquiryData(
                l.accountNo(), l.date(),
                l.dailyLimit(), l.dailyUsed(), l.dailyRemaining(),
                l.monthlyLimit(), l.monthlyUsed(), l.monthlyRemaining(),
                l.perTxLimit(), l.limitTier()));
    }

    @Override
    public BalanceTxData withdraw(String idempotencyKey, WithdrawRequest request) {
        return toTxData(transactionService.withdraw(
                request.accountId(),
                request.amount(),
                channelOf(request.channelType()),
                request.transactionMemo(),
                idempotencyKey));
    }

    @Override
    public BalanceTxData deposit(String idempotencyKey, DepositRequest request) {
        return toTxData(transactionService.deposit(
                request.accountId(),
                request.amount(),
                channelOf(request.channelType()),
                request.transactionMemo(),
                null,
                request.depositorName(),
                idempotencyKey));
    }

    @Override
    public WithdrawCancelData withdrawCancel(String idempotencyKey, Long transactionId) {
        Transaction reversal = transactionService.reversal(transactionId, TransactionChannel.SYSTEM, idempotencyKey);
        return new WithdrawCancelData(
                reversal.getTransactionNumber(),
                null,   // 원거래 번호: deposit 은 originalTransactionId(FK)만 보유
                null,   // 계좌번호: Transaction 에 없다(accountId 만)
                reversal.getBalanceBefore(),
                reversal.getBalanceAfter(),
                reversal.getTransactionAt() != null ? reversal.getTransactionAt().toString() : null);
    }

    private BalanceTxData toTxData(Transaction t) {
        return new BalanceTxData(
                t.getTransactionId(),
                t.getTransactionNumber(),
                null,   // 계좌번호: Transaction 에 없다. HTTP 시절에도 미제공이었다.
                t.getAmount(),
                t.getBalanceBefore(),
                t.getBalanceAfter(),
                t.getTransactionAt() != null ? t.getTransactionAt().toString() : null,
                t.getTransactionType() != null ? t.getTransactionType().name() : null);
    }

    /** 채널 문자열이 비었거나 알 수 없으면 SYSTEM 으로 본다(결제계가 부른 거래이므로). */
    private TransactionChannel channelOf(String channelType) {
        if (channelType == null || channelType.isBlank()) {
            return TransactionChannel.SYSTEM;
        }
        try {
            return TransactionChannel.valueOf(channelType);
        } catch (IllegalArgumentException e) {
            return TransactionChannel.SYSTEM;
        }
    }

    private <T> DepositResponse<T> ok(T data) {
        return new DepositResponse<>(SUCCESS_CODE, "SUCCESS",
                java.time.OffsetDateTime.now().toString(), data);
    }
}
