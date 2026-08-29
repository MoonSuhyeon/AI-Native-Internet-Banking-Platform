package com.bank.payment.domain.service;

import com.bank.common.time.BusinessDate;
import com.bank.payment.common.IdGenerator;
import com.bank.payment.domain.IdempotencyKey;
import com.bank.payment.domain.Ledger;
import com.bank.payment.domain.mapper.IdempotencyKeyMapper;
import com.bank.payment.domain.mapper.LedgerMapper;
import com.bank.payment.outbound.ledger.AccountQueryPort;
import com.bank.payment.outbound.ledger.LedgerPort;
import com.bank.payment.outbound.ledger.dto.AccountInquiryData;
import com.bank.payment.outbound.ledger.dto.BalanceTxData;
import com.bank.payment.outbound.ledger.dto.DepositRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대출 실행 — 여신 도메인의 독립적인 회계 거래.
 *
 * <p><b>왜 결제 경로를 쓰지 않는가.</b> 은행 회계에서 대출 실행은 돈을 옮기는 일이
 * 아니다. 자산(대출채권)이 생기고 부채(고객 예금)가 생기는 하나의 사건이다.
 *
 * <pre>
 *   DEBIT   대출채권 (KB-LOAN-CR)   자산 증가
 *   CREDIT  고객 예금계좌            부채 증가
 * </pre>
 *
 * <p>집행계좌는 이 분개에 등장하지 않는다. 예전에는 집행계좌에서 고객계좌로의
 * 이체로 처리해 실행할 때마다 집행계좌 잔액이 줄었다 — 자금부가 계속 채워 넣어야
 * 하는 구조인데 그것은 대출 실행의 회계가 아니다.
 *
 * <p>근거: {@code docs/decisions/transaction-initiator-auth-model.md} §5-1.
 *
 * <p><b>무엇을 바꾸지 않는가.</b> 실제 자금이 오가는 거래 — 상환·자동이체·역분개
 * 환급 — 는 {@code /api/v1/internal/payments} 를 그대로 쓴다. 경로가 갈려도
 * 인가·FDS·멱등·감사·원장은 공통으로 지난다. 갈리는 것은 회계적 의미이지 통제가 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanDisbursementService {

    private final IdempotencyKeyMapper idempotencyKeyMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerPort ledgerPort;
    private final AccountQueryPort accountQueryPort;
    private final IdGenerator idGenerator;

    /**
     * 대출 실행을 원장에 기록한다.
     *
     * <p>하나의 트랜잭션이다. 예금이 늘었는데 분개가 없거나, 분개는 있는데 예금이
     * 안 늘어난 상태가 생기면 안 된다. 결제계의 자행이체와 같은 이유로 단일
     * 트랜잭션으로 묶는다.
     *
     * @param idempotencyKey 여신이 발급한 키({@code EXEC-…}). 같은 키로 다시 오면
     *                       {@code idempotency_key} PK 위반으로 막힌다
     * @param serviceId      호출한 서비스. 인가에서 세운 신원이다
     * @return 분개 묶음 번호와 실행 후 고객 잔액
     */
    @Transactional
    public LoanDisbursementResult disburse(String idempotencyKey, String serviceId,
                                           String accountNo, Long amount, String memo) {

        OffsetDateTime now = OffsetDateTime.now();
        String businessDate = BusinessDate.of(now);

        // 1) 멱등 claim. 결제계 txStep1 과 같은 방식이다 — 같은 키가 다시 오면
        //    PK 위반으로 여기서 멎고, 트랜잭션이 통째로 롤백된다.
        idempotencyKeyMapper.insert(IdempotencyKey.of(
                idempotencyKey, serviceId, "", now, now, now.plusMinutes(5)));

        // 2) 고객 계좌 확인. 없는 계좌면 여기서 멎는다.
        //    예금주명은 별도 조회다 — 계좌 조회 응답에 담기지 않는다.
        AccountInquiryData account = accountQueryPort.getAccountByNo(accountNo);
        String holderName = accountQueryPort.getHolder(accountNo).holderName();

        // 3) 고객 예금 증가. 부채가 늘어나는 쪽이다.
        BalanceTxData deposited = ledgerPort.deposit(idempotencyKey, new DepositRequest(
                account.accountId(), amount, "SYSTEM", memo, "대출실행"));

        // 4) 분개 두 다리. 같은 journal_no 로 묶어 한 회계 사건임을 남긴다.
        String journalNo = idGenerator.nextJournalNo();

        Ledger receivable = Ledger.loanReceivable(
                idGenerator.nextLedgerId(), journalNo, amount,
                "KRW", businessDate, businessDate, businessDate, now, memo);

        Ledger customerDeposit = Ledger.loanDisburseDeposit(
                idGenerator.nextLedgerId(), account.accountId().toString(), journalNo,
                accountNo, holderName == null ? "" : holderName,
                amount, deposited.balanceBefore(), deposited.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate, now, memo);

        ledgerMapper.insert(receivable);
        ledgerMapper.insert(customerDeposit);

        // 5) 저장된 행을 다시 읽어 균형을 확인한다. 메모리 객체를 비교하면
        //    INSERT 가 조용히 빠진 경우를 못 잡는다 — DoubleEntryVerifier 주석 참고.
        List<Ledger> saved = ledgerMapper.selectByJournalNo(journalNo);
        DoubleEntryVerifier.verify(saved, "대출실행 " + idempotencyKey);

        log.info("대출 실행 기장 idemKey={} journalNo={} accountNo={} amount={}",
                idempotencyKey, journalNo, accountNo, amount);

        return new LoanDisbursementResult(journalNo, deposited.balanceAfter());
    }

    /**
     * @param journalNo    분개 묶음 번호. 여신이 이것을 자기 회계번호로 저장한다
     * @param balanceAfter 실행 후 고객 계좌 잔액
     */
    public record LoanDisbursementResult(String journalNo, Long balanceAfter) {
    }
}
