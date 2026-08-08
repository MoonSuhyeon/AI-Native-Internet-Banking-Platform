package com.bank.fds.detect;

/**
 * 탐지 신호 하나.
 *
 * <p><b>왜 점수 하나가 아니라 신호 목록인가.</b> 심사원에게 "위험도 0.82" 만 주면
 * 판단할 수 없다. 무엇이 왜 걸렸는지가 있어야 한다. 조사 에이전트도 이 신호들을
 * 가설의 출발점으로 쓴다.
 *
 * <p>{@code detail} 은 사람이 읽을 근거다 — "평소 최대 50만원, 이번 300만원" 처럼
 * 판단에 필요한 값을 담는다. 지표 라벨로는 쓰지 않는다(카디널리티).
 *
 * @param code     신호 식별자. 지표 라벨로 쓰므로 값의 종류가 제한적이어야 한다.
 * @param severity 신호 강도. 등급 판정의 입력이다.
 * @param detail   사람이 읽을 근거 문장.
 */
public record DetectionSignal(String code, Severity severity, String detail) {

    public enum Severity {
        /** 단독으로는 조치하지 않는다. 누적·조합으로만 의미를 갖는다. */
        LOW,
        /** 추가인증을 요구할 정도. */
        MEDIUM,
        /** 사람이 봐야 할 정도. */
        HIGH,
        /**
         * 규제·법령이 직접 요구하는 신호.
         *
         * <p>점수와 무관하게 반드시 사람에게 간다. 요주의인물 적중처럼
         * "모델이 낮게 봤으니 넘긴다" 가 성립하지 않는 항목이다.
         */
        MANDATORY
    }

    public static DetectionSignal of(String code, Severity severity, String detail) {
        return new DetectionSignal(code, severity, detail);
    }
}
