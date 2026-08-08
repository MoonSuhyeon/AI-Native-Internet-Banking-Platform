package com.bank.fds.detect;

import com.bank.fds.detect.DetectionSignal.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 신호 목록에서 대응 등급을 정한다.
 *
 * <p><b>왜 점수 합산이 아닌가.</b> 가중합으로 한 숫자를 만들면 임계값 하나로 모든 걸
 * 가르게 되는데, 규제가 요구하는 항목은 그렇게 다룰 수 없다. 요주의인물 적중은
 * "모델이 낮게 봤으니 통과" 가 성립하지 않는다 — 점수와 무관하게 사람에게 가야 한다.
 * 그래서 {@link Severity#MANDATORY} 는 다른 신호를 보지 않고 곧장 등급을 정한다.
 *
 * <p><b>나머지는 강도와 개수로 본다.</b> 약한 신호 하나로 고객을 붙잡으면 오탐이 쌓여
 * 심사원이 지치고, 결국 큐 전체가 형식적으로 처리된다 — 실무에서 FDS 가 실패하는
 * 전형적인 방식이다. 그래서 LOW 는 단독으로 조치하지 않고 누적으로만 의미를 갖는다.
 *
 * <p>임계 개수는 설정으로 뺄 수 있지만, 지금은 코드에 둔다. 운영 중 자주 바뀌는 값은
 * 임계 <b>금액·시간창</b>이지 이 구조가 아니다.
 */
@Component
public class ResponseTierPolicy {

    /**
     * @param signals 이번 거래에서 나온 신호. 비어 있으면 PASS.
     * @param inline  사전(승인 전) 판정이면 true. 사후면 false —
     *                끝난 거래는 막을 수 없으므로 BLOCK 이 지급정지 권고로 바뀐다.
     */
    public ResponseTier decide(List<DetectionSignal> signals, boolean inline) {
        if (signals == null || signals.isEmpty()) {
            return ResponseTier.PASS;
        }

        ResponseTier tier = bySeverity(signals);

        // 규제 필수 신호는 <b>하한</b>이다. 상한이 아니다.
        //
        // 예전에는 MANDATORY 를 만나면 곧장 HOLD_REVIEW 를 반환했는데, 그러면 다른
        // 신호가 BLOCK 을 가리켜도 낮춰 버렸다 — 규제 항목이 함께 걸린 더 위험한 건이
        // 오히려 약하게 처리되는 모양이었다. 최소 사람 검토는 보장하되, 더 강한 판정은
        // 그대로 둔다.
        if (has(signals, Severity.MANDATORY)) {
            tier = tier.strongerOf(ResponseTier.HOLD_REVIEW);
        }

        return adjust(tier, inline);
    }

    /** 강도와 개수만으로 본 등급. */
    private ResponseTier bySeverity(List<DetectionSignal> signals) {
        long high = count(signals, Severity.HIGH);
        long medium = count(signals, Severity.MEDIUM);

        // HIGH 가 겹치면 단순 의심이 아니다. 사전이면 막고, 사후면 지급정지를 권고한다.
        if (high >= 2) {
            return ResponseTier.BLOCK;
        }
        if (high == 1) {
            // HIGH 하나 + 다른 신호가 받쳐 주면 사람이 본다.
            return medium >= 1 ? ResponseTier.HOLD_REVIEW : ResponseTier.DELAY;
        }
        if (medium >= 2) {
            // 중간 신호가 겹치면 인증만으로는 부족하다. 되돌릴 시간을 준다.
            return ResponseTier.DELAY;
        }
        if (medium == 1) {
            // 본인 확인을 요구한다. 막지는 않는다 — 정상 고객의 대부분이 여기 걸린다.
            return ResponseTier.STEP_UP;
        }

        // LOW 만 남았다. 단독으로는 조치하지 않는다 — 누적으로만 의미가 있다.
        return ResponseTier.MONITOR;
    }

    /** 사후 판정이면 실행 가능한 등급으로 옮긴다. */
    private ResponseTier adjust(ResponseTier tier, boolean inline) {
        return inline ? tier : tier.forPostHoc();
    }

    private boolean has(List<DetectionSignal> signals, Severity severity) {
        return signals.stream().anyMatch(s -> s.severity() == severity);
    }

    private long count(List<DetectionSignal> signals, Severity severity) {
        return signals.stream().filter(s -> s.severity() == severity).count();
    }
}
