package com.bank.payment.domain.reconciliation;

/**
 * 대사에 넣을 양쪽 기록.
 *
 * <p>원장 엔티티나 청산 엔티티를 그대로 쓰지 않고 좁은 형태로 옮겨 담는 이유는,
 * 대사가 봐야 하는 것이 <b>결제 단위의 금액과 상태</b> 뿐이기 때문이다. 스냅샷·시각·
 * 상대은행 같은 필드가 섞여 있으면 대사 로직이 무엇을 근거로 판단했는지 읽기 어렵다.
 */
public final class ClearingRecords {

    private ClearingRecords() {
    }

    /**
     * 내부 원장 쪽 — 이 결제의 청산대기 순액.
     *
     * <p><b>순액인 것이 중요하다.</b> 취소된 거래는 청산대기(대변)와 그 역분개(차변)가
     * 같은 금액으로 남으므로 순액이 0 이 된다. 총액만 보면 취소된 건도 "청산대기가
     * 있다" 로 잡혀 정상 거래와 구별되지 않는다.
     *
     * @param paymentInstructionId 결제지시 ID
     * @param netAmount            청산대기 대변 합 − 차변 합. 취소되었으면 0
     */
    public record Internal(String paymentInstructionId, long netAmount) {

        /** 내부적으로 취소된 거래인가. */
        public boolean reversed() {
            return netAmount == 0L;
        }
    }

    /**
     * 외부 청산 쪽 — 망이 알려준 금액과 상태.
     *
     * @param paymentInstructionId 결제지시 ID
     * @param amount               청산·정산 금액
     * @param status               REQUESTED · ACK · ACK_RECEIVED · SETTLED · REJECTED · TIMEOUT
     */
    public record External(String paymentInstructionId, long amount, String status) {

        /** 더 이상 바뀌지 않는 상태인가. 아니면 영업일 마감 시점에 미결로 본다. */
        public boolean settled() {
            return "SETTLED".equals(status);
        }

        /**
         * 확정된 상태인가.
         *
         * <p>TIMEOUT 을 확정으로 보는 이유는, 그것이 이미 "응답이 오지 않았다" 는
         * 판정을 내린 결과이기 때문이다. 다시 미결로 잡으면 같은 사실이 두 번
         * 보고된다.
         */
        public boolean finalized() {
            return settled() || "REJECTED".equals(status) || "TIMEOUT".equals(status);
        }
    }
}
