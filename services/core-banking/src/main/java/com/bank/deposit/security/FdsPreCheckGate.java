package com.bank.deposit.security;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    /**
     * 단계적 도입. 기본은 꺼 둔다.
     *
     * <p>결제 임계 경로에 새 외부 호출을 넣는 변경이라, 탐지기가 안정적으로 뜨는 것을
     * 확인한 뒤 켠다. TransferApprovalGate 도 같은 방식으로 도입했다.
     */
    @Value("${deposit.fds.enabled:false}")
    private boolean enabled;

    @Autowired
    public FdsPreCheckGate(
            RestClient.Builder builder,
            AmountTierPolicy amountTierPolicy,
            @Value("${deposit.fds.base-url:http://fds-detector:8092}") String baseUrl,
            @Value("${deposit.fds.timeout-ms:400}") int timeoutMs) {

        this.amountTierPolicy = amountTierPolicy;

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
    FdsPreCheckGate(RestClient restClient, AmountTierPolicy amountTierPolicy) {
        this.restClient = restClient;
        this.amountTierPolicy = amountTierPolicy;
    }

    /**
     * @param hasApprovalToken 승인 토큰을 이미 제시했는가. 탐지기가 추가 인증을 요구했을 때
     *                         이미 인증했으면 다시 막지 않기 위해 필요하다.
     */
    public void evaluate(String senderUserId, String senderAccountNo, String receiverBankCode,
                         String receiverAccountNo, Long amount, Boolean intraBank,
                         String channel, boolean hasApprovalToken) {

        if (!enabled) {
            return;
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
            return;
        }

        if (result == null || result.tier() == null) {
            log.warn("이상거래 점검 응답이 비었다 amountTier={}", tier);
            applyOutagePolicy(tier);
            return;
        }

        // 탐지기가 축소 판정했다고 알려 온 경우도 장애와 같이 다룬다.
        // "이상 없음" 과 "판정을 못 했다" 는 다른 사건인데, 응답이 성공이라고
        // 후자를 전자로 읽으면 고액이 점검 없이 나간다.
        if (result.degraded()) {
            log.warn("이상거래 점검 축소 판정 amountTier={} signals={}", tier, result.signals());
            applyOutagePolicy(tier);
            return;
        }

        applyTier(result, hasApprovalToken);
    }

    /** 판정 결과대로 조치한다. */
    private void applyTier(PreCheckResult result, boolean hasApprovalToken) {
        switch (result.tier()) {
            case "PASS", "MONITOR" -> {
                // 통과. MONITOR 는 표시만 하는 등급이라 거래를 막지 않는다.
            }
            case "STEP_UP", "DELAY" -> {
                // 본인 확인을 요구한다. 이미 승인 토큰을 제시했으면 다시 막지 않는다.
                //
                // DELAY 를 STEP_UP 과 같이 처리한다. 진짜 지연이체는 보류 후 재개하는
                // 장치가 필요한데 아직 없다 — 그때까지 더 약한 쪽이 아니라
                // 인증 요구로 처리한다. 지표에는 DELAY 로 남으므로 얼마나 필요한지 보인다.
                if (!hasApprovalToken) {
                    log.info("이상거래 점검이 추가 인증을 요구함 tier={} signals={}",
                            result.tier(), result.signals());
                    throw new BusinessException(ErrorCode.TRANSFER_APPROVAL_REQUIRED);
                }
            }
            default -> {
                // BLOCK / HOLD_REVIEW / FREEZE_RECOMMEND — 사람이 봐야 하는 등급이다.
                // 인라인에서는 보류할 수단이 없으므로 진행하지 않는다.
                log.warn("이상거래로 이체 차단 tier={} signals={}", result.tier(), result.signals());
                throw new BusinessException(ErrorCode.TRANSFER_BLOCKED_BY_RISK);
            }
        }
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
    record PreCheckResult(String tier, List<Object> signals, boolean degraded) {
    }
}
