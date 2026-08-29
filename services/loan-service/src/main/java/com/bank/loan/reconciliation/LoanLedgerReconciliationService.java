package com.bank.loan.reconciliation;

import com.bank.loan.accounting.domain.DailyAccountingSummary;
import com.bank.loan.accounting.repository.DailyAccountingSummaryRepository;
import com.bank.loan.payment.client.PaymentServiceClient;
import com.bank.loan.payment.client.dto.LoanLedgerSummary;
import com.bank.loan.reconciliation.LoanLedgerReconciler.LoanReconciliationInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 여신 보조부와 원장을 맞춰 본다. EOD 배치의 마지막 관문이다.
 *
 * <p><b>왜 이것이 필요한가.</b> 복식부기는 우리 장부 안에서 차변과 대변이 맞는지만
 * 본다. 여신이 자기 보조부에 적은 숫자가 원장과 맞는지는 전혀 보장하지 않는다.
 * 실행 한 건이 보조부에만 적히고 원장에 안 남거나 그 반대인 상태는, 각자를 아무리
 * 들여다봐도 보이지 않는다.
 *
 * <p><b>왜 보조부를 지우지 않았는가.</b> {@code DailyAccountingSummary} 는 소비처가
 * 없어 지울 후보로 보였다. 그러나 소비처가 없다는 것은 삭제 이유가 아니라 통제가
 * 아직 안 만들어졌다는 뜻이었고, 그 통제가 이것이다. 양쪽이 <b>독립적으로</b>
 * 산출해야 대사가 성립한다 — 한쪽을 다른 쪽에서 뽑아 오면 항등식이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLedgerReconciliationService {

    private final DailyAccountingSummaryRepository summaryRepository;
    private final LoanLedgerReconciliationRepository reconciliationRepository;
    private final PaymentServiceClient paymentServiceClient;

    /**
     * 하루치를 대사하고 결과를 남긴다.
     *
     * <p><b>불일치라고 배치를 멈추지 않는다.</b> 멈추면 뒤따르는 단계가 통째로 밀리고,
     * 그러면 다음 날 대사도 못 한다 — 발견을 위한 장치가 발견을 막는 셈이 된다.
     * 대신 기록을 남기고 경고한다. 그 기록을 보는 것이 마감 담당자의 일이다.
     *
     * @return 대사 결과. 불일치면 {@code result=MISMATCH}
     */
    @Transactional
    public LoanLedgerReconciliation reconcile(String baseDate) {

        // 보조부 — 여신이 자기 거래에서 집계한 값
        DailyAccountingSummary subledger = summaryRepository.findBySummaryDate(baseDate).orElse(null);

        long subDisbursedAmount = subledger == null ? 0L : subledger.getDisbursedAmount();
        long subDisbursedCount  = subledger == null ? 0L : subledger.getDisbursedCount();
        // 상환은 원금·이자·연체이자가 한 건으로 나간다. 원장에는 그 합계가 하나의
        // 출금 분개로 남으므로 보조부도 합쳐서 견준다.
        long subRepaidAmount = subledger == null ? 0L
                : subledger.getAutoDebitPrincipal()
                + subledger.getAutoDebitInterest()
                + subledger.getAutoDebitOverdueInterest();
        long subRepaidCount = subledger == null ? 0L : subledger.getAutoDebitCount();

        // 원장 — 정본. core-banking 이 원장에서만 계산해 넘긴다
        LoanLedgerSummary ledger = paymentServiceClient.loanLedgerSummary(baseDate);
        if (ledger == null) {
            // 조회 실패와 "거래가 없었다" 는 다른 사건이다. 0 으로 갈음하면 원장이
            // 비었다고 단정하게 되고, 그날 실행이 있었다면 거짓 불일치가 난다.
            throw new IllegalStateException("원장 집계를 읽지 못했다 baseDate=" + baseDate);
        }

        List<String> breaks = LoanLedgerReconciler.reconcile(new LoanReconciliationInput(
                baseDate,
                subDisbursedAmount, subDisbursedCount, subRepaidAmount, subRepaidCount,
                nz(ledger.disbursedAmount()), nz(ledger.disbursedCount()),
                nz(ledger.repaidAmount()), nz(ledger.repaidCount())));

        LoanLedgerReconciliation fresh = LoanLedgerReconciliation.builder()
                .baseDate(baseDate)
                .subledgerDisbursedAmount(subDisbursedAmount)
                .subledgerDisbursedCount((int) subDisbursedCount)
                .subledgerRepaidAmount(subRepaidAmount)
                .subledgerRepaidCount((int) subRepaidCount)
                .ledgerDisbursedAmount(nz(ledger.disbursedAmount()))
                .ledgerDisbursedCount((int) nz(ledger.disbursedCount()))
                .ledgerRepaidAmount(nz(ledger.repaidAmount()))
                .ledgerRepaidCount((int) nz(ledger.repaidCount()))
                .result(breaks.isEmpty()
                        ? LoanLedgerReconciliation.RESULT_MATCH
                        : LoanLedgerReconciliation.RESULT_MISMATCH)
                .breakDetail(breaks.isEmpty() ? null : String.join(" · ", breaks))
                .build();

        LoanLedgerReconciliation saved = reconciliationRepository.findByBaseDate(baseDate)
                .map(existing -> {
                    existing.replaceWith(fresh);
                    return existing;
                })
                .orElseGet(() -> reconciliationRepository.save(fresh));

        if (saved.isMismatch()) {
            // 마감 담당자가 봐야 하는 사건이다. 조용히 넘어가면 대사가 있는 것이
            // 없는 것보다 나빠진다 — 맞는 줄 알게 되기 때문이다.
            log.error("[EOD][대사] 여신 보조부와 원장이 어긋난다 baseDate={} {}",
                    baseDate, saved.getBreakDetail());
        } else {
            log.info("[EOD][대사] 일치 baseDate={} 실행={} 상환={}",
                    baseDate, subDisbursedAmount, subRepaidAmount);
        }
        return saved;
    }

    private static long nz(Number v) {
        return v == null ? 0L : v.longValue();
    }
}
