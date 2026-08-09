package com.bank.payment.domain.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대사 — 내부 원장의 청산대기와 외부 청산 기록을 결제 단위로 맞춰 본다.
 *
 * <p><b>왜 필요한가.</b> 복식부기는 <i>우리 장부 안에서</i> 차변과 대변이 맞는지만 본다.
 * 우리 장부가 <b>바깥세상과 맞는지</b>는 전혀 보장하지 않는다. 출금 분개와 청산대기
 * 분개는 완벽하게 균형을 이루면서도, 정작 그 돈이 망으로 나가지 않았을 수 있다.
 * 그 상태는 원장만 봐서는 영원히 안 보인다 — 대사가 유일한 발견 수단이다.
 *
 * <p><b>정산된 건의 청산대기가 남아 있는 것은 불일치가 아니다.</b> KFTC 정산은
 * 청산대기를 되돌리는 분개를 남기지 않고, 그 unwind 를 하위 회계계의 몫으로 넘긴다
 * (PaymentTransactionService#txSettlement 참조). 그래서 "청산계정이 0 이 되어야 한다"
 * 로 대사를 짜면 <b>정상 거래가 전부 불일치로 잡힌다</b>. 여기서는 금액과 상태를
 * 맞춰 보는 것이지, 잔액이 풀렸는지를 보지 않는다.
 *
 * <p>순수 함수인 이유는 이 판단 자체를 DB 없이 검증하기 위해서다. 대사 로직이 틀리면
 * 조용히 "이상 없음" 을 보고하게 되는데, 그것은 대사가 없는 것보다 나쁘다.
 */
public final class ReconciliationEngine {

    private ReconciliationEngine() {
    }

    /**
     * 양쪽을 맞춰 보고 어긋난 것만 돌려준다.
     *
     * @param internal  내부 원장의 결제별 청산대기 순액
     * @param external  외부 청산 기록
     * @param dayClosed 이 영업일이 이미 끝났는가. 끝났으면 미확정 상태를 미결로 본다.
     *                  진행 중인 날에 대해 켜면 정상 거래가 전부 잡히므로 호출부가
     *                  영업일과 오늘을 비교해 넘긴다.
     * @return 불일치 목록. 심각한 것부터 정렬된다 — 수백 건이 나와도 무엇부터 볼지
     *         정해져 있어야 하기 때문이다.
     */
    public static List<ReconciliationBreak> reconcile(
            List<ClearingRecords.Internal> internal,
            List<ClearingRecords.External> external,
            boolean dayClosed) {

        Map<String, ClearingRecords.Internal> internalByPi = new LinkedHashMap<>();
        for (ClearingRecords.Internal i : nullSafe(internal)) {
            internalByPi.put(i.paymentInstructionId(), i);
        }
        Map<String, ClearingRecords.External> externalByPi = new LinkedHashMap<>();
        for (ClearingRecords.External e : nullSafe(external)) {
            externalByPi.put(e.paymentInstructionId(), e);
        }

        // 양쪽 어느 한쪽에만 있는 것도 봐야 하므로 합집합을 돈다.
        // 교집합만 돌면 가장 위험한 두 유형(한쪽 누락)을 통째로 놓친다.
        Set<String> allPis = new LinkedHashSet<>(internalByPi.keySet());
        allPis.addAll(externalByPi.keySet());

        List<ReconciliationBreak> breaks = new ArrayList<>();

        for (String piId : allPis) {
            ClearingRecords.Internal in = internalByPi.get(piId);
            ClearingRecords.External ex = externalByPi.get(piId);

            if (ex == null) {
                // 내부에만 있다. 취소된 건이면 애초에 망에 안 갔을 수 있으니 정상이다.
                if (in != null && !in.reversed()) {
                    breaks.add(new ReconciliationBreak(
                            piId, ReconciliationBreakType.MISSING_EXTERNAL,
                            in.netAmount(), 0L, null,
                            "내부 원장에 청산대기가 있으나 외부 청산 기록이 없다 — "
                                    + "출금됐는데 망으로 나가지 않았을 수 있다"));
                }
                continue;
            }

            if (in == null) {
                // 외부에만 있다. 거절된 건은 내부에 남을 이유가 없으므로 정상이다.
                if (!"REJECTED".equals(ex.status())) {
                    breaks.add(new ReconciliationBreak(
                            piId, ReconciliationBreakType.MISSING_INTERNAL,
                            0L, ex.amount(), ex.status(),
                            "외부 청산 기록이 있으나 내부 원장에 청산대기가 없다 — "
                                    + "망은 처리했는데 장부에 남지 않았다"));
                }
                continue;
            }

            if (in.reversed()) {
                // 내부에서 되돌렸다. 망도 거절했다면 앞뒤가 맞는다.
                if (ex.settled()) {
                    breaks.add(new ReconciliationBreak(
                            piId, ReconciliationBreakType.REVERSED_BUT_SETTLED,
                            0L, ex.amount(), ex.status(),
                            "내부에서 취소했으나 외부는 정산 완료 — "
                                    + "장부는 되돌렸는데 망에서는 돈이 나갔다"));
                }
                continue;
            }

            if (in.netAmount() != ex.amount()) {
                breaks.add(new ReconciliationBreak(
                        piId, ReconciliationBreakType.AMOUNT_MISMATCH,
                        in.netAmount(), ex.amount(), ex.status(),
                        "금액 불일치 — 내부 " + in.netAmount() + " ≠ 외부 " + ex.amount()));
                continue;
            }

            // 금액은 맞는다. 남은 것은 "언제까지나 안 끝나는" 건이다.
            if (dayClosed && !ex.finalized()) {
                breaks.add(new ReconciliationBreak(
                        piId, ReconciliationBreakType.STALE_PENDING,
                        in.netAmount(), ex.amount(), ex.status(),
                        "영업일이 끝났으나 외부 상태가 " + ex.status() + " 로 미확정이다"));
            }
        }

        // 심각도(enum 선언 순) → 금액 큰 것 순.
        breaks.sort((a, b) -> {
            int byType = a.type().compareTo(b.type());
            if (byType != 0) {
                return byType;
            }
            return Long.compare(Math.abs(b.difference()), Math.abs(a.difference()));
        });
        return breaks;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
