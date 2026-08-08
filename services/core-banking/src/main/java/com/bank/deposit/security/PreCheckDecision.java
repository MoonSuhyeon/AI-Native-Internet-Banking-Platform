package com.bank.deposit.security;

import java.time.Duration;
import java.util.List;

/**
 * 이상거래 사전 점검의 결론 중 <b>거래를 계속 진행할 수 있는</b> 경우.
 *
 * <p>차단·인증요구는 예외로 끊는다. 계속 진행하되 방식이 달라지는 경우만 이 값으로
 * 돌려준다 — 지금은 그대로 진행(PROCEED)과 지연(DELAY) 둘이다.
 *
 * <p><b>왜 지연을 예외로 처리하지 않는가.</b> 지연은 거절이 아니다. 거래는 접수됐고
 * 잠시 뒤 실행된다. 예외로 끊으면 호출부가 "실패" 로 다루게 되고, 고객에게도
 * 실패로 보인다 — 실제로는 취소할 기회를 준 것인데 그 의미가 사라진다.
 */
public record PreCheckDecision(Kind kind, Duration delay, List<String> reasons) {

    public enum Kind {
        /** 그대로 진행. */
        PROCEED,
        /**
         * 지연 후 실행.
         *
         * <p>보이스피싱 대응의 지연이체와 같은 성격이다. 차단만큼 강하지 않으면서
         * 고객이 알아채고 되돌릴 시간을 준다.
         */
        DELAY
    }

    public static PreCheckDecision proceed() {
        return new PreCheckDecision(Kind.PROCEED, Duration.ZERO, List.of());
    }

    public static PreCheckDecision delay(Duration delay, List<String> reasons) {
        return new PreCheckDecision(Kind.DELAY, delay, reasons);
    }

    public boolean isDelayed() {
        return kind == Kind.DELAY;
    }
}
