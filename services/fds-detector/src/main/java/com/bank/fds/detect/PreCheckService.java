package com.bank.fds.detect;

import com.bank.fds.api.dto.PreCheckRequest;
import com.bank.fds.api.dto.PreCheckResponse;
import com.bank.fds.guidance.GuidanceComposer;
import com.bank.fds.detect.DetectionSignal.Severity;
import com.bank.fds.observability.FdsMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 사전 점검 — 결제 승인 전에 부르는 인라인 판정.
 *
 * <p><b>여기서는 계산하지 않는다.</b> 예산이 수백 ms 라 프로파일 재계산이나 외부 호출을
 * 할 수 없다. 사후 탐지·배치가 미리 만들어 둔 값을 <b>조회</b>해서 판정한다.
 * 실무 FDS 가 무거운 것을 오프라인으로 미루는 이유가 이것이다.
 *
 * <p><b>지금 보는 신호는 임시다.</b> 고객 프로파일(평소 금액·시간대·기기 대비 편차)이
 * 들어오기 전까지 절대 금액 같은 약한 기준을 쓴다. "1천만원 넘으면 의심" 은 실무에서
 * 잘 쓰지 않는다 — 평소 5만원 쓰던 사람의 300만원이 훨씬 강한 신호다.
 * 프로파일이 붙으면 이 자리를 대체한다.
 */
@Service
@RequiredArgsConstructor
public class PreCheckService {

    private final RiskStateStore riskStateStore;
    private final ResponseTierPolicy tierPolicy;
    private final FdsMetrics metrics;
    private final GuidanceComposer guidanceComposer;

    @Value("${fds.rule.high-amount:10000000}")
    private long highAmountThreshold;

    @Value("${fds.rule.velocity-threshold:5}")
    private long velocityThreshold;

    /** 1일 누적 보고 기준. 규제 항목이라 점수와 무관하게 사람에게 간다. */
    @Value("${fds.rule.daily-cumulative-threshold:20000000}")
    private long dailyCumulativeThreshold;

    public PreCheckResponse evaluate(PreCheckRequest req) {
        List<DetectionSignal> signals = new ArrayList<>();

        // 사후 탐지가 이 고객을 이미 올려 뒀는가. 두 계층을 잇는 지점이다.
        Optional<ResponseTier> elevated = riskStateStore.elevatedTier(req.senderUserId());
        elevated.ifPresent(tier -> signals.add(DetectionSignal.of(
                "ELEVATED_RISK_STATE",
                tier.requiresHumanReview() ? Severity.HIGH : Severity.MEDIUM,
                "사후 탐지가 최근 " + tier + " 로 표시한 고객")));

        // 단기 시도 횟수. 승인되지 않은 시도도 센다 — 반복 자체가 신호다.
        Optional<Long> velocity = riskStateStore.touchVelocity(req.senderUserId());
        velocity.filter(c -> c > velocityThreshold).ifPresent(c -> signals.add(DetectionSignal.of(
                "VELOCITY",
                Severity.MEDIUM,
                "짧은 시간 내 이체 시도 " + c + "건")));

        // 절대 금액 — 프로파일이 들어오기 전까지의 임시 기준.
        if (req.amount() != null && req.amount() >= highAmountThreshold) {
            signals.add(DetectionSignal.of(
                    "HIGH_AMOUNT",
                    Severity.MEDIUM,
                    "고액 이체 " + req.amount() + "원"));
        }

        // 오늘 누적 + 이번 건이 보고 기준을 넘는가. 읽기만 한다 — 더하는 것은
        // 거래가 실제로 완료된 뒤 사후가 한다.
        if (req.amount() != null) {
            riskStateStore.dailyAmount(req.senderUserId())
                    .map(sofar -> sofar + req.amount())
                    .filter(total -> total >= dailyCumulativeThreshold)
                    .ifPresent(total -> signals.add(DetectionSignal.of(
                            "DAILY_CUMULATIVE_THRESHOLD", Severity.MANDATORY,
                            "1일 누적 예상 " + total + "원 — 보고 기준 초과")));
        }

        ResponseTier tier = tierPolicy.decide(signals, true);
        signals.forEach(s -> metrics.ruleHit(s.code()));
        metrics.tierDecided(tier.name(), "inline");

        // 조회가 온전했는지 알려 준다. "통과" 와 "판정을 못 해서 통과" 는 다른 사건이라,
        // 결제계가 금액 구간별 장애 정책을 적용할 수 있어야 한다.
        boolean degraded = !riskStateStore.isAvailable();
        if (degraded) {
            return PreCheckResponse.degraded(tier, signals);
        }

        // 고객 화면에 뜨는 등급에만 안내를 만든다. 통과하는 거래에까지 붙이면
        // 아무도 읽지 않을 문자열을 매 이체마다 만드는 셈이다.
        return tier == ResponseTier.PASS || tier == ResponseTier.MONITOR
                ? PreCheckResponse.of(tier, signals)
                : PreCheckResponse.of(tier, signals, guidanceComposer.compose(tier, signals));
    }
}
