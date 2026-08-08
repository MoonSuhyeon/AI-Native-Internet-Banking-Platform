package com.bank.fds.detect;

import com.bank.common.time.BusinessDate;
import com.bank.fds.detect.DetectionSignal.Severity;
import com.bank.fds.enrich.PaymentDetail;
import com.bank.fds.observability.FdsMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 완료된 거래를 다시 본다.
 *
 * <p><b>사전 점검과 무엇이 다른가.</b> 사전은 예산이 수백 ms 라 조회만 한다. 여기는
 * 거래가 끝난 뒤라 시간을 쓸 수 있고, 결제계에서 상세를 보강해 받았으므로 수취인·
 * 시간대·자행여부까지 본다. 실무 FDS 의 준실시간 계층이 하는 일이다.
 *
 * <p><b>여기서 만든 결과가 다음 거래의 사전 점검을 먹인다.</b> 사람 손이 필요한 등급이
 * 나오면 고객을 한동안 표시해 두고, 사전 점검이 그것을 읽는다. 이 연결이 없으면
 * 무거운 분석이 아무 데도 쓰이지 않는다.
 *
 * <p><b>절대 금액 기준은 임시다.</b> 고객 프로파일(평소 금액 대비 편차)이 들어오면
 * 대체한다 — 평소 5만원 쓰던 사람의 300만원이 1천만원 단건보다 강한 신호다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostHocDetectionService {

    private final RiskStateStore riskStateStore;
    private final ResponseTierPolicy tierPolicy;
    private final FdsMetrics metrics;

    @Value("${fds.rule.high-amount:10000000}")
    private long highAmountThreshold;

    /** 심야 구간 시작(포함). KST 기준. */
    @Value("${fds.rule.night-from-hour:0}")
    private int nightFromHour;

    /** 심야 구간 끝(제외). */
    @Value("${fds.rule.night-to-hour:6}")
    private int nightToHour;

    /**
     * @return 사람 손이 필요하면 결과, 아니면 조치 없이 끝난 결과.
     */
    public DetectionOutcome detect(PaymentDetail detail) {
        List<DetectionSignal> signals = new ArrayList<>();

        if (detail.amount() != null && detail.amount() >= highAmountThreshold) {
            signals.add(DetectionSignal.of("HIGH_AMOUNT", Severity.MEDIUM,
                    "고액 이체 " + detail.amount() + "원"));
        }

        if (isNightTime(detail)) {
            // 심야 자체는 흔하다. 다른 신호와 겹칠 때 의미가 생기므로 약하게 둔다.
            signals.add(DetectionSignal.of("NIGHT_TIME", Severity.LOW,
                    "심야 시간대 거래"));
        }

        String receiverKey = receiverKey(detail);
        if (riskStateStore.isFirstTimeReceiver(detail.senderUserId(), receiverKey)) {
            // 신규 수취인은 지극히 정상이다(이사, 첫 거래처). 금액·시간대와 겹칠 때만
            // 의미가 있어 약하게 둔다.
            signals.add(DetectionSignal.of("NEW_RECEIVER", Severity.LOW,
                    "처음 보내는 수취인 " + receiverKey));
        }

        if (Boolean.FALSE.equals(detail.intraBank())
                && detail.amount() != null && detail.amount() >= highAmountThreshold) {
            // 타행 고액은 되돌리기 어렵다. 자행이면 우리 안에서 정지시킬 수 있지만
            // 타행은 상대 은행 협조가 필요해 시간이 훨씬 오래 걸린다.
            signals.add(DetectionSignal.of("CROSS_BANK_HIGH_AMOUNT", Severity.MEDIUM,
                    "타행 고액 이체"));
        }

        // 끝난 거래는 막을 수 없다. inline=false 로 판정해 BLOCK 이 지급정지 권고로 올라간다.
        ResponseTier tier = tierPolicy.decide(signals, false);

        signals.forEach(s -> metrics.ruleHit(s.code()));
        metrics.tierDecided(tier.name(), "posthoc");

        // 조치가 필요한 등급이면 고객을 표시해 둔다. 다음 거래의 사전 점검이 이 표시를
        // 읽는다 — 무거운 분석 결과가 실시간 통제로 이어지는 유일한 통로다.
        //
        // 사람 검토가 필요한 등급만 남기면 STEP_UP·DELAY 가 전달되지 않는다.
        // 정작 "이 고객을 한동안 조심하자" 에 해당하는 등급들이라, 그것들이 빠지면
        // 사전 점검은 사건이 터진 뒤에야 뭔가를 읽게 된다.
        if (tier != ResponseTier.PASS && tier != ResponseTier.MONITOR) {
            riskStateStore.elevate(detail.senderUserId(), tier);
        }

        if (tier.requiresHumanReview()) {
            metrics.caseCreated("rule");
            log.info("사건화 piId={} tier={} signals={}",
                    detail.paymentInstructionId(), tier, signals.size());
        }

        return new DetectionOutcome(tier, signals);
    }

    private boolean isNightTime(PaymentDetail detail) {
        if (detail.completedAt() == null) {
            return false;
        }
        // 은행 업무 기준 시각은 KST 다. UTC 로 판정하면 9시간이 밀려
        // 한국의 아침이 심야로 잡힌다.
        LocalTime kst = detail.completedAt().atZoneSameInstant(BusinessDate.ZONE).toLocalTime();
        return !kst.isBefore(LocalTime.of(nightFromHour, 0))
                && kst.isBefore(LocalTime.of(nightToHour, 0));
    }

    private String receiverKey(PaymentDetail detail) {
        String bank = detail.receiverBankCode() == null ? "" : detail.receiverBankCode();
        String acc = detail.receiverAccountNo() == null ? "" : detail.receiverAccountNo();
        return bank + ":" + acc;
    }

    /** 판정 결과. 조사 투입 여부는 {@link ResponseTier#requiresHumanReview()} 로 판단한다. */
    public record DetectionOutcome(ResponseTier tier, List<DetectionSignal> signals) {
    }
}
