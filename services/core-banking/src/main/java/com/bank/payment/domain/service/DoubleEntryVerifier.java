package com.bank.payment.domain.service;

import com.bank.payment.common.exception.LedgerBalanceMismatchException;
import com.bank.payment.domain.Ledger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복식부기 검증 — 분개 묶음(journal_no)마다 차변 합 = 대변 합.
 *
 * <p><b>왜 새로 만들었나.</b> 검증은 원래도 여덟 군데에 있었다. 다만 여섯 곳이
 * 이런 모양이었다.
 *
 * <pre>{@code
 * Ledger out = Ledger.intraTransferOut(..., amount, ...);   // 차변
 * Ledger in  = Ledger.intraTransferIn (..., amount, ...);   // 대변
 * ...
 * if (out.getAmount().compareTo(in.getAmount()) != 0) {     // amount vs amount
 *     throw new LedgerBalanceMismatchException(...);
 * }
 * }</pre>
 *
 * <p>두 분개가 <b>같은 지역변수</b>로 만들어졌으므로 이 비교는 변수를 자기 자신과
 * 견주는 것이고, 참이 될 수 없다. 컴파일도 되고 로그도 남고 예외 클래스까지 갖췄지만
 * 잡는 것은 없었다 — 검증이 있다고 믿게 만드는 것이 없는 것보다 나쁘다.
 *
 * <p><b>무엇을 확인해야 하는가.</b> 복식부기가 막으려는 것은 "같은 숫자를 두 번 썼는가"
 * 가 아니라 <b>원장에 실제로 균형 잡힌 기록이 남았는가</b> 이다. 그래서 여기서는
 * 저장된 행을 다시 읽어 확인한다. 그러면 다음이 걸린다.
 *
 * <ul>
 *   <li>INSERT 가 조용히 빠진 경우 — 한쪽 다리만 남는다. 이 코드베이스에는 그 상황을
 *       재현하는 {@code ledgerFailureSimulator} 가 이미 있다. 즉 일어날 수 있다고
 *       알고 있으면서 메모리 객체만 비교하고 있었다.</li>
 *   <li>차/대 구분이 잘못 붙은 경우 — 두 다리가 다 DEBIT 이면 금액은 같아도 균형이
 *       깨진다. 예전 비교는 금액만 봐서 통과시킨다.</li>
 *   <li>매퍼가 엉뚱한 컬럼에 써서 금액이 달라진 경우 — 메모리 객체는 멀쩡하다.</li>
 * </ul>
 *
 * <p>순수 함수로 분리한 이유는 이것 자체를 DB 없이 검증하기 위해서다. 검증 코드가
 * 틀리면 위 세 가지를 다시 놓친다.
 */
public final class DoubleEntryVerifier {

    private DoubleEntryVerifier() {
    }

    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    /**
     * 분개 묶음별로 차변 합 = 대변 합인지 확인한다.
     *
     * <p>역분개가 원분개와 같은 {@code journal_no} 를 쓰는 것은 의도된 설계다. 그때도
     * 묶음 합계는 그대로 균형을 유지한다(원 차변+역 차변 = 원 대변+역 대변). 그래서
     * 역분개를 따로 제외하지 않는다 — 제외하면 오히려 "취소된 거래" 를 검증에서
     * 빠뜨리게 된다.
     *
     * @param ledgers 이 결제로 원장에 저장된 <b>모든</b> 분개 (읽어 온 것이어야 한다)
     * @param context 예외 메시지에 남길 식별자 (보통 결제지시 ID)
     * @throws LedgerBalanceMismatchException 균형이 깨졌거나 한쪽 다리가 없을 때
     */
    public static void verify(List<Ledger> ledgers, String context) {
        if (ledgers == null || ledgers.isEmpty()) {
            // 분개를 남기는 경로에서 한 건도 없다는 것은 전부 실패했다는 뜻이다.
            // 빈 목록을 "균형 잡힘" 으로 통과시키면 그 사고가 조용히 지나간다.
            throw new LedgerBalanceMismatchException(
                    "분개가 한 건도 없다 (" + context + ")");
        }

        Map<String, long[]> sums = new LinkedHashMap<>();   // journalNo → [차변, 대변]
        Set<String> unknownIndicators = new LinkedHashSet<>();

        for (Ledger l : ledgers) {
            long[] pair = sums.computeIfAbsent(l.getJournalNo(), k -> new long[2]);
            String drCr = l.getDebitCredit();
            long amount = l.getAmount() == null ? 0L : l.getAmount();

            if (DEBIT.equals(drCr)) {
                pair[0] += amount;
            } else if (CREDIT.equals(drCr)) {
                pair[1] += amount;
            } else {
                // 구분이 비었거나 오타면 어느 쪽에도 안 더해진다. 그대로 두면
                // 합계가 0 대 0 으로 맞아떨어져 "균형" 으로 보이므로 따로 잡는다.
                unknownIndicators.add(String.valueOf(drCr));
            }
        }

        if (!unknownIndicators.isEmpty()) {
            throw new LedgerBalanceMismatchException(
                    "차/대 구분이 DEBIT·CREDIT 이 아니다: " + unknownIndicators
                            + " (" + context + ")");
        }

        sums.forEach((journalNo, pair) -> {
            if (pair[0] != pair[1]) {
                throw new LedgerBalanceMismatchException(
                        "차변≠대변: 묶음 " + journalNo
                                + " DEBIT " + pair[0] + " ≠ CREDIT " + pair[1]
                                + " (" + context + ")");
            }
            if (pair[0] == 0L) {
                // 0 = 0 은 산술적으로 균형이지만 회계적으로는 기록이 없는 것이다.
                // 양쪽 INSERT 가 모두 빠져도 여기 걸리게 한다.
                throw new LedgerBalanceMismatchException(
                        "묶음 " + journalNo + " 의 금액이 0 이다 — 분개가 남지 않았다 ("
                                + context + ")");
            }
        });
    }
}
