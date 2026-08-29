package com.bank.loan.reconciliation;

import java.util.ArrayList;
import java.util.List;

/**
 * 여신 보조부와 원장을 맞춰 본다.
 *
 * <p><b>왜 필요한가.</b> 복식부기는 <i>우리 장부 안에서</i> 차변과 대변이 맞는지만 본다.
 * 여신이 자기 보조부에 적은 숫자가 원장과 맞는지는 전혀 보장하지 않는다. 실행 한 건이
 * 보조부에만 적히고 원장에 안 남거나 그 반대인 상태는 각자를 아무리 들여다봐도
 * 보이지 않는다 — 두 계통을 맞춰 보는 것이 유일한 발견 수단이다.
 *
 * <p><b>그래서 보조부를 지우지 않았다.</b> 소비처가 없다는 것은 삭제 이유가 아니라
 * 통제가 아직 안 만들어졌다는 뜻이었고, 그 통제가 이것이다. 한쪽을 다른 쪽에서 뽑아
 * 오면 대사는 항등식이 되어 아무것도 검증하지 못한다.
 *
 * <p>순수 함수인 이유는 이 판단 자체를 DB 없이 검증하기 위해서다. 대사 로직이 틀리면
 * 조용히 "이상 없음" 을 보고하게 되는데, 그것은 대사가 없는 것보다 나쁘다 —
 * 결제계의 {@code ReconciliationEngine} 과 같은 이유다.
 */
public final class LoanLedgerReconciler {

    private LoanLedgerReconciler() {
    }

    /**
     * 양쪽을 맞춰 보고 어긋난 항목만 문장으로 돌려준다.
     *
     * <p>금액과 건수를 모두 본다. <b>금액만 보면 놓치는 것이 있다</b> — 같은 날 두 건이
     * 서로 반대로 어긋나면 합계는 맞는데 건수가 다르다. 그 경우 원장과 보조부가
     * 다른 사건을 세고 있다는 뜻이라, 합계가 맞는 것이 오히려 위험하다.
     *
     * @return 빈 목록이면 일치. 항목이 있으면 각각이 어긋난 지점이다
     */
    public static List<String> reconcile(LoanReconciliationInput input) {
        List<String> breaks = new ArrayList<>();

        compare(breaks, "실행액",
                input.subledgerDisbursedAmount(), input.ledgerDisbursedAmount());
        compare(breaks, "실행건수",
                input.subledgerDisbursedCount(), input.ledgerDisbursedCount());
        compare(breaks, "상환액",
                input.subledgerRepaidAmount(), input.ledgerRepaidAmount());
        compare(breaks, "상환건수",
                input.subledgerRepaidCount(), input.ledgerRepaidCount());

        return breaks;
    }

    /**
     * 한 항목을 견준다.
     *
     * <p>어느 쪽이 무엇이라고 말했는지를 함께 적는다. 차액만 적으면 나중에 볼 때
     * 원인을 좁힐 수 없다 — 보조부가 더 센 것과 원장이 덜 센 것은 다른 사건이다.
     */
    private static void compare(List<String> breaks, String label, long subledger, long ledger) {
        if (subledger != ledger) {
            breaks.add("%s 보조부=%d 원장=%d 차이=%d"
                    .formatted(label, subledger, ledger, subledger - ledger));
        }
    }

    /**
     * 대사 입력. 양쪽이 각자 산출한 값이다.
     *
     * <p>{@code null} 을 받지 않는다. 집계가 없었던 날은 0 이고, 그것과 "조회하지
     * 못했다" 는 다른 사건이라 호출부가 구분해 넘겨야 한다.
     */
    public record LoanReconciliationInput(
            String baseDate,
            long subledgerDisbursedAmount,
            long subledgerDisbursedCount,
            long subledgerRepaidAmount,
            long subledgerRepaidCount,
            long ledgerDisbursedAmount,
            long ledgerDisbursedCount,
            long ledgerRepaidAmount,
            long ledgerRepaidCount
    ) {
    }
}
