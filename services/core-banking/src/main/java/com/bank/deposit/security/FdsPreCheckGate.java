package com.bank.deposit.security;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.RiskGuidedException;
import com.bank.deposit.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 이체 승인 <b>전</b> 이상거래 점검.
 *
 * <p><b>왜 결제 경로에 넣는가.</b> 사후 탐지는 이미 나간 돈을 막지 못한다. 보이스피싱은
 * 사후에 잡아도 늦다. 실무 FDS 의 주력은 승인 전 인라인 판정이고, 그래서 은행은
 * 이 호출을 결제 임계 경로에 둔다.
 *
 * <p><b>대신 임계 경로에 있다는 사실을 잊지 않는다.</b>
 * <ul>
 *   <li>타임아웃을 짧게 건다. 탐지기가 느려지면 모든 이체가 느려진다.</li>
 *   <li>판정을 못 받았을 때 <b>금액 구간으로</b> 갈린다. 소액까지 멈추면 탐지기 장애가
 *       곧 결제 장애가 되고, 고액을 판정 없이 통과시키면 FDS 를 둔 의미가 없다.</li>
 * </ul>
 *
 * <p><b>구간 기준은 {@link AmountTierPolicy} 하나를 공유한다.</b> 승인 강도와 이 판단이
 * 각각 다른 "고액" 을 쓰면 같은 거래가 한쪽에선 고액이고 한쪽에선 아니게 된다.
 */
@Slf4j
@Component
public class FdsPreCheckGate {

    private final RestClient restClient;
    private final AmountTierPolicy amountTierPolicy;
    private final MeterRegistry meterRegistry;

    /**
     * 단계적 도입. 기본은 꺼 둔다.
     *
     * <p>결제 임계 경로에 새 외부 호출을 넣는 변경이라, 탐지기가 안정적으로 뜨는 것을
     * 확인한 뒤 켠다. TransferApprovalGate 도 같은 방식으로 도입했다.
     */
    @Value("${deposit.fds.enabled:false}")
    private boolean enabled;

    /**
     * 지연 시간(분).
     *
     * <p>짧으면 고객이 알아챌 시간이 없고, 길면 정상 거래가 불편해진다.
     * 실제 은행의 지연이체 제도도 이 절충 위에 있다.
     */
    @Value("${deposit.fds.delay-minutes:30}")
    private long delayMinutes;

    @Autowired
    public FdsPreCheckGate(
            RestClient.Builder builder,
            AmountTierPolicy amountTierPolicy,
            MeterRegistry meterRegistry,
            @Value("${deposit.fds.base-url:http://fds-detector:8092}") String baseUrl,
            @Value("${deposit.fds.timeout-ms:400}") int timeoutMs) {

        this.amountTierPolicy = amountTierPolicy;
        this.meterRegistry = meterRegistry;

        // 인라인 판정이라 예산이 밀리초 단위다. 기본 타임아웃은 무제한이어서
        // 걸지 않으면 탐지기가 응답만 안 해도 이체 스레드가 그대로 묶인다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 테스트 seam. 준비된 RestClient 를 그대로 쓴다.
     *
     * <p>운영 생성자는 타임아웃을 걸려고 {@code requestFactory} 를 직접 지정하는데,
     * 그러면 목 서버가 심어 둔 팩토리를 덮어써 실제 호출이 나가 버린다.
     */
    FdsPreCheckGate(RestClient restClient, AmountTierPolicy amountTierPolicy,
                    MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.amountTierPolicy = amountTierPolicy;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 사전 점검이 거래를 어떻게 처리했는가.
     *
     * <p>게이트에서 센다. 호출부(즉시이체·예약이체·자행이체) 세 곳을 한 번에 덮고,
     * 어느 경로로 들어와도 같은 기준으로 집계된다.
     *
     * <p><b>지연 장치의 성능은 이 값만으로는 안 나온다.</b> 얼마나 지연시켰는가가
     * 아니라 지연된 건 중 실제로 취소된 비율이 핵심인데, 그러려면 예약 건에
     * "FDS 가 지연시킨 것" 표시가 남아야 한다. 아직 없다 — 다음 작업이다.
     */
    private void countOutcome(String outcome) {
        meterRegistry.counter("payment.fds.precheck.outcome", "outcome", outcome).increment();
    }

    /**
     * 지연 지시를 호출부가 이행하지 못했다.
     *
     * <p>자행이체 경로에는 타행이체가 쓰는 예약 실행이 없어서 지연을 못 지킨다.
     * 예전에는 반환값을 받지도 않아 <b>지시가 흔적 없이 사라졌다</b> — 통과와
     * 구별되지 않으니 지연 장치가 얼마나 새는지 알 수 없었다.
     *
     * <p>계측을 여기 둔 이유는 FDS 지표가 한 곳에 모여 있어야 하기 때문이다.
     * 호출부마다 레지스트리를 들고 다니면 이름이 갈라지고, 컨트롤러가 계측 배선을
     * 생성자에 이고 가게 된다.
     */
    public void reportDelayNotHonored(String path, List<String> reasons) {
        log.warn("지연 지시를 이행하지 못하고 즉시 실행함 path={} reasons={}", path, reasons);
        meterRegistry.counter("payment.fds.delay.not_honored", "path", path).increment();
    }

    /**
     * @param hasApprovalToken 승인 토큰을 이미 제시했는가. 탐지기가 추가 인증을 요구했을 때
     *                         이미 인증했으면 다시 막지 않기 위해 필요하다.
     */
    public PreCheckDecision evaluate(String senderUserId, String senderAccountNo, String receiverBankCode,
                                     String receiverAccountNo, Long amount, Boolean intraBank,
                                     String channel, boolean hasApprovalToken) {

        if (!enabled) {
            return PreCheckDecision.proceed();
        }

        AmountTier tier = amountTierPolicy.of(amount);

        PreCheckResult result;
        try {
            result = restClient.post()
                    .uri("/api/v1/internal/fds/precheck")
                    .body(Map.of(
                            "senderUserId", nullSafe(senderUserId),
                            "senderAccountNo", nullSafe(senderAccountNo),
                            "receiverBankCode", nullSafe(receiverBankCode),
                            "receiverAccountNo", nullSafe(receiverAccountNo),
                            "amount", amount == null ? 0L : amount,
                            "intraBank", intraBank != null && intraBank,
                            "channel", nullSafe(channel)))
                    .retrieve()
                    .body(PreCheckResult.class);
        } catch (Exception e) {
            log.warn("이상거래 점검 실패 amountTier={} reason={}", tier, e.toString());
            applyOutagePolicy(tier);
            return PreCheckDecision.proceed();
        }

        if (result == null || result.tier() == null) {
            log.warn("이상거래 점검 응답이 비었다 amountTier={}", tier);
            applyOutagePolicy(tier);
            return PreCheckDecision.proceed();
        }

        // 탐지기가 축소 판정했다고 알려 온 경우도 장애와 같이 다룬다.
        // "이상 없음" 과 "판정을 못 했다" 는 다른 사건인데, 응답이 성공이라고
        // 후자를 전자로 읽으면 고액이 점검 없이 나간다.
        if (result.degraded()) {
            log.warn("이상거래 점검 축소 판정 amountTier={} signals={}", tier, result.signals());
            applyOutagePolicy(tier);
            return PreCheckDecision.proceed();
        }

        return applyTier(result, hasApprovalToken);
    }

    /** 판정 결과대로 조치한다. */
    private PreCheckDecision applyTier(PreCheckResult result, boolean hasApprovalToken) {
        switch (result.tier()) {
            case "PASS", "MONITOR" -> {
                // 통과. MONITOR 는 표시만 하는 등급이라 거래를 막지 않는다.
                countOutcome("proceed");
                return PreCheckDecision.proceed();
            }
            case "STEP_UP" -> {
                // 본인 확인을 요구한다. 이미 승인 토큰을 제시했으면 다시 막지 않는다.
                if (!hasApprovalToken) {
                    log.info("이상거래 점검이 추가 인증을 요구함 signals={}", result.signals());
                    countOutcome("step_up_required");
                    throw new RiskGuidedException(ErrorCode.TRANSFER_APPROVAL_REQUIRED, result.guidance());
                }
                countOutcome("proceed");
                return PreCheckDecision.proceed();
            }
            case "DELAY" -> {
                // 거절이 아니다. 접수는 하되 실행을 미뤄, 고객이 알아채고 취소할
                // 시간을 준다 — 보이스피싱 대응의 지연이체와 같은 성격이다.
                log.info("이상거래 점검이 지연을 지시함 delayMinutes={} signals={}",
                        delayMinutes, result.signals());
                countOutcome("delayed");
                return PreCheckDecision.delay(
                        Duration.ofMinutes(delayMinutes), toReasons(result.signals()), result.guidance());
            }
            default -> {
                // BLOCK / HOLD_REVIEW / FREEZE_RECOMMEND — 사람이 봐야 하는 등급이다.
                // 지연으로도 부족하다. 진행하지 않는다.
                // 예전에는 여기서 근거를 로그에만 남기고 고정 문구를 던졌다. 탐지기가
                // 만들어 보낸 근거가 고객에게 도달하지 않아, 화면에는 "고객센터로
                // 문의해 주세요" 만 떴다 — 왜 막혔는지도, 지금 무엇을 할지도 없이.
                log.warn("이상거래로 이체 차단 tier={} signals={}", result.tier(), result.signals());
                countOutcome("blocked");
                throw new RiskGuidedException(ErrorCode.TRANSFER_BLOCKED_BY_RISK, result.guidance());
            }
        }
    }

    /** 신호를 사람이 읽을 사유로 옮긴다. 고객 안내와 감사 기록에 쓴다. */
    private List<String> toReasons(List<Object> signals) {
        if (signals == null) {
            return List.of();
        }
        return signals.stream().map(String::valueOf).toList();
    }

    /**
     * 판정을 받지 못했을 때. 금액 구간으로 갈린다.
     *
     * <p>소액을 멈추지 않는 이유는 탐지기 장애가 결제 장애로 번지는 것이 더 큰 사고이기
     * 때문이다. 고액을 통과시키지 않는 이유는 그 경우 FDS 가 사실상 꺼져 있는 것과
     * 같기 때문이다.
     */
    private void applyOutagePolicy(AmountTier tier) {
        if (tier.failClosedOnDetectorOutage()) {
            throw new BusinessException(ErrorCode.TRANSFER_RISK_CHECK_UNAVAILABLE);
        }
    }

    private String nullSafe(String v) {
        return v == null ? "" : v;
    }

    /**
     * 탐지기 응답. 필요한 것만 받는다.
     *
     * <p>{@code ignoreUnknown} 을 명시한다. Boot 기본값에 기대면, 누군가 역직렬화 설정을
     * 엄격하게 바꾸는 순간 탐지기가 필드 하나 늘렸다고 이체가 전부 막힌다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PreCheckResult(String tier, List<Object> signals, boolean degraded, RiskGuidance guidance) {
    }
}
