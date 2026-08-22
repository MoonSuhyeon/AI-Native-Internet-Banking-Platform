package com.bank.fds.api.dto;

import com.bank.fds.detect.DetectionSignal;
import com.bank.fds.detect.ResponseTier;
import com.bank.fds.guidance.ConsumerGuidance;

import java.util.List;

/**
 * 사전 점검 결과.
 *
 * <p><b>왜 신호 목록까지 주는가.</b> 결제계는 등급만 있으면 동작할 수 있다. 그런데
 * 거래가 막히거나 추가인증을 요구받은 이유가 남지 않으면, 나중에 고객이 항의했을 때
 * 무엇 때문이었는지 재구성할 수 없다. 금융 분쟁에서 그 기록은 필수다.
 *
 * @param tier    결제계가 따라야 할 조치.
 * @param signals 판정 근거. 감사 기록과 심사원 화면에 쓴다.
 * @param degraded 판정이 온전했는지. true 면 프로파일 조회 실패 등으로 <b>축소 판정</b>
 *                 했다는 뜻이다. 결제계는 이 값을 보고 구간별 장애 정책을 적용한다 —
 *                 "통과"와 "판정을 못 해서 통과"는 전혀 다른 사건이다.
 * @param guidance 고객에게 보여 줄 안내. <b>신호를 그대로 넘기는 것만으로는 부족해서</b>
 *                 함께 싣는다 — 결제계는 신호 코드의 의미를 모르고, 알게 하면 규칙이
 *                 두 곳에 흩어진다. 통과 등급에서는 {@code null} 이다.
 */
public record PreCheckResponse(
        ResponseTier tier,
        List<DetectionSignal> signals,
        boolean degraded,
        ConsumerGuidance guidance
) {

    public static PreCheckResponse of(ResponseTier tier, List<DetectionSignal> signals) {
        return new PreCheckResponse(tier, signals, false, null);
    }

    /** 안내까지 얹은 판정. 고객 화면에 뜨는 등급에서 쓴다. */
    public static PreCheckResponse of(ResponseTier tier, List<DetectionSignal> signals,
                                      ConsumerGuidance guidance) {
        return new PreCheckResponse(tier, signals, false, guidance);
    }

    /**
     * 판정을 온전히 하지 못했다. 결제계가 구간별 정책으로 마무리한다.
     *
     * <p>안내를 싣지 않는다. 축소 판정은 "신호가 없다" 가 아니라 "보지 못했다" 라서,
     * 근거라고 부를 것이 없다. 없는 근거로 안내를 지어내면 그게 더 나쁘다.
     */
    public static PreCheckResponse degraded(ResponseTier tier, List<DetectionSignal> signals) {
        return new PreCheckResponse(tier, signals, true, null);
    }
}
