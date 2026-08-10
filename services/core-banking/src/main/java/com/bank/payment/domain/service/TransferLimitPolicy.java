package com.bank.payment.domain.service;

/**
 * 이체한도 판정 — 1회 한도와 1일 누적 한도.
 *
 * <p><b>두 종류의 한도가 겹친다.</b>
 * <ul>
 *   <li><b>고객당 인터넷뱅킹 한도</b> (customer-service) — 그 사람이 인터넷뱅킹으로
 *       보낼 수 있는 1일/1회 금액. 온라인에서는 감액만 가능하다.</li>
 *   <li><b>계좌당 출금한도</b> (deposit) — 그 계좌에서 하루에 나갈 수 있는 총액.</li>
 * </ul>
 *
 * <p>둘은 별개 개념이라 <b>낮은 쪽</b>이 걸린다. 화면 고지사항도 그렇게 안내한다.
 * 한쪽만 보면 다른 쪽 약속을 어기게 된다 — 한도계좌로 낮춰 둔 고객이 인터넷뱅킹
 * 한도로 통과하거나, 그 반대가 된다.
 *
 * <p><b>계좌가 아니라 고객으로 누적한다.</b> 한도가 고객당이므로 계좌로 집계하면
 * 계좌를 두 개 가진 사람이 한도를 두 배로 쓴다.
 *
 * <p>순수 함수로 둔 이유는 이 판정을 DB·HTTP 없이 검증하기 위해서다. 금액 비교는
 * 경계에서 틀리기 쉽고(초과인가 이상인가, 누적에 이번 건을 포함하는가), 틀려도
 * 대부분의 거래는 정상으로 흐르므로 조용히 남는다.
 */
public final class TransferLimitPolicy {

    private TransferLimitPolicy() {
    }

    /** 판정 결과. 통과면 {@link #ok()}, 아니면 사유가 담긴다. */
    public record Decision(boolean allowed, String reason) {

        public static Decision ok() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * 이번 이체를 허용할지 판단한다.
     *
     * <p>모든 한도는 {@code null} 이면 "설정 없음" 으로 보고 건너뛴다. 0 이나 음수도
     * 마찬가지다 — 한도 0 을 "아무것도 못 보냄" 으로 읽으면 설정 실수 하나로 전 고객의
     * 이체가 막힌다.
     *
     * @param amount        이번 이체 금액 (수수료 제외 — 고객이 화면에서 보는 금액)
     * @param todayTotal    오늘 이미 나간 누적액 (이번 건 제외)
     * @param customerDaily 고객당 1일 한도
     * @param customerOnce  고객당 1회 한도
     * @param accountDaily  계좌당 1일 출금한도
     */
    public static Decision evaluate(long amount,
                                    long todayTotal,
                                    Long customerDaily,
                                    Long customerOnce,
                                    Long accountDaily) {

        // 1회 한도 — 이번 건 하나만 본다.
        if (isSet(customerOnce) && amount > customerOnce) {
            return Decision.deny(
                    "1회 이체한도 초과: 요청 " + amount + " > 1회 한도 " + customerOnce);
        }

        // 1일 한도 — 오늘 나간 것에 이번 건을 더해서 본다.
        //
        // 이번 건을 빼고 비교하면 한도에 딱 걸친 고객이 한 번 더 보낼 수 있다.
        // "이미 넘었는가" 가 아니라 "보내고 나면 넘는가" 를 물어야 한다.
        long afterThis = todayTotal + amount;

        Long daily = lowerOf(customerDaily, accountDaily);
        if (isSet(daily) && afterThis > daily) {
            return Decision.deny(
                    "1일 이체한도 초과: 오늘 누적 " + todayTotal + " + 요청 " + amount
                            + " = " + afterThis + " > 1일 한도 " + daily);
        }

        return Decision.ok();
    }

    /**
     * 두 한도 중 낮은 쪽. 한쪽만 설정돼 있으면 그쪽을 쓴다.
     *
     * <p>낮은 쪽을 쓰는 이유는 두 한도가 각각 다른 약속이기 때문이다. 높은 쪽을 쓰면
     * 낮은 쪽 약속이 무효가 된다.
     */
    static Long lowerOf(Long a, Long b) {
        if (!isSet(a)) {
            return isSet(b) ? b : null;
        }
        if (!isSet(b)) {
            return a;
        }
        return Math.min(a, b);
    }

    private static boolean isSet(Long limit) {
        return limit != null && limit > 0;
    }
}
