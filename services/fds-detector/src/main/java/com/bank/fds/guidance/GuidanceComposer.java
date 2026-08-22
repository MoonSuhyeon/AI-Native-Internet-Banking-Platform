package com.bank.fds.guidance;

import com.bank.fds.detect.DetectionSignal;
import com.bank.fds.detect.ResponseTier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 신호와 등급을 고객 안내로 옮긴다.
 *
 * <p><b>왜 신호마다 다른 행동요령인가.</b> 같은 "차단" 이라도 고객이 해야 할 일이
 * 다르기 때문이다.
 *
 * <ul>
 *   <li>고액을 처음 보는 곳으로 보내는 중이면 &mdash; <b>상대방에게 직접 전화해 확인</b>.
 *       보이스피싱은 이 한 통으로 대부분 끊긴다</li>
 *   <li>짧은 시간에 반복 시도가 있었으면 &mdash; <b>본인이 한 것이 맞는지</b>부터.
 *       아니면 계정이 이미 남의 손에 있다</li>
 *   <li>규제 보고 기준을 넘었으면 &mdash; 위험이 아니라 <b>절차</b>다. 겁줄 일이 아니다</li>
 * </ul>
 *
 * <p>이걸 한 문장으로 뭉뚱그리면 안내가 아니라 통보가 된다. 지금까지 그랬다.
 *
 * <p><b>왜 등급이 문구를 바꾸는가.</b> 지연은 거절이 아니다. 거래는 접수됐고 취소할
 * 시간이 있다는 것이 요점인데, 차단과 같은 문구를 쓰면 그 의미가 사라진다 &mdash;
 * {@code PreCheckDecision} 주석이 같은 이유로 지연을 예외로 던지지 않는다.
 *
 * <p><b>연령대는 여기서 다루지 않는다.</b> 이 판정은 결제 경로 안에서 일어나고
 * 탐지기는 고객 생년월일을 모른다. 알려면 조회가 한 번 더 붙어 예산을 먹는다.
 * 연령대에 맞춘 말투·난이도는 화면이 조사 에이전트에게 따로 물어 덧입힌다
 * ({@code ConsumerGuidance} 주석 참고). 여기서 만드는 것은 <b>누가 읽어도 성립하는
 * 기본형</b>이다.
 */
@Component
public class GuidanceComposer {

    /** 이 신호가 보이면 상대방 확인이 최우선이다. 보이스피싱의 표준 대응. */
    private static final Set<String> CALL_THE_RECIPIENT_FIRST = Set.of("HIGH_AMOUNT");

    public ConsumerGuidance compose(ResponseTier tier, List<DetectionSignal> signals) {
        List<DetectionSignal> safe = signals == null ? List.of() : signals;
        Set<String> codes = new LinkedHashSet<>();
        safe.forEach(s -> codes.add(s.code()));

        return new ConsumerGuidance(
                headline(tier, codes),
                safe.stream().map(DetectionSignal::detail).filter(d -> d != null && !d.isBlank()).toList(),
                actionSteps(tier, codes),
                choices(tier));
    }

    /**
     * 왜 멈췄는지 한 줄.
     *
     * <p>등급이 먼저다. 고객에게 가장 먼저 필요한 정보는 "무슨 신호가 걸렸나" 가
     * 아니라 <b>"내 돈이 지금 어떤 상태인가"</b> 이기 때문이다.
     */
    private String headline(ResponseTier tier, Set<String> codes) {
        return switch (tier) {
            case STEP_UP -> "본인 확인이 한 번 더 필요합니다";
            case DELAY -> "이체를 잠시 미뤘습니다. 지금 취소할 수 있습니다";
            case HOLD_REVIEW -> "확인이 필요해 이체를 진행하지 않았습니다";
            case BLOCK, FREEZE_RECOMMEND -> codes.contains("HIGH_AMOUNT")
                    ? "최근 피해 사례와 비슷한 패턴이라 이체를 멈췄습니다"
                    : "이상 신호가 있어 이체를 멈췄습니다";
            // PASS·MONITOR 는 화면에 뜨지 않는다. 안내를 만들 일이 없지만,
            // 새 등급이 생겼을 때 조용히 빈 문자열이 나가지 않게 값은 채운다.
            case PASS, MONITOR -> "정상 거래로 확인됐습니다";
        };
    }

    /**
     * 지금 할 일.
     *
     * <p>순서가 곧 우선순위다. 첫 줄이 가장 급한 것이어야 한다 &mdash; 목록을 끝까지
     * 읽지 않는 사람이 대부분이고, 이 화면은 특히 당황한 상태에서 읽힌다.
     */
    private List<String> actionSteps(ResponseTier tier, Set<String> codes) {
        List<String> steps = new ArrayList<>();

        if (codes.stream().anyMatch(CALL_THE_RECIPIENT_FIRST::contains)) {
            steps.add("보내기 전에 받는 분에게 직접 전화해 확인하세요. "
                      + "문자나 메신저로 온 계좌번호는 바뀌었을 수 있습니다.");
        }
        if (codes.contains("VELOCITY")) {
            steps.add("짧은 시간에 여러 번 이체가 시도됐습니다. 본인이 한 것이 아니라면 "
                      + "즉시 비밀번호를 바꾸고 고객센터(1588-9999)로 알려 주세요.");
        }
        if (codes.contains("ELEVATED_RISK_STATE")) {
            steps.add("최근 거래에서도 위험 신호가 있었습니다. 모르는 사람의 요청으로 "
                      + "보내는 것이라면 지금 멈추세요.");
        }
        if (codes.contains("DAILY_CUMULATIVE_THRESHOLD")) {
            steps.add("오늘 보낸 금액이 보고 기준을 넘었습니다. 위험하다는 뜻은 아니고 "
                      + "확인 절차가 필요한 금액입니다.");
        }

        // 신호를 하나도 해석하지 못했을 때. 규칙이 늘었는데 여기를 안 고친 경우다.
        // 빈 목록으로 내보내면 화면에 '지금 할 일' 이 사라져 예전 상태로 돌아간다.
        if (steps.isEmpty()) {
            steps.add("보내는 것이 확실하지 않다면 잠시 멈추고 받는 분에게 직접 확인하세요.");
        }

        if (tier == ResponseTier.DELAY) {
            steps.add("이체는 잠시 뒤 실행됩니다. 그 전에 취소하면 돈은 나가지 않습니다.");
        }
        return steps;
    }

    /**
     * 화면이 줄 선택지.
     *
     * <p><b>상담 연결은 항상 있다.</b> 이 화면은 "막혔는데 어떻게 하지" 로 끝나면 안
     * 된다 &mdash; 그 막다른 골목이 애초에 고치려던 문제다.
     */
    private List<String> choices(ResponseTier tier) {
        if (tier == ResponseTier.STEP_UP) {
            return List.of(
                    ConsumerGuidance.Choice.STEP_UP.name(),
                    ConsumerGuidance.Choice.CANCEL.name(),
                    ConsumerGuidance.Choice.CONSULT.name());
        }
        // 차단·지연에서는 추가 인증으로 뚫을 수 없다. 뚫린다면 그건 인증이 아니라
        // 재시도이고, 실제로 보이스피싱 피해자는 시키는 대로 인증을 통과한다.
        return List.of(
                ConsumerGuidance.Choice.CANCEL.name(),
                ConsumerGuidance.Choice.CONSULT.name());
    }
}
