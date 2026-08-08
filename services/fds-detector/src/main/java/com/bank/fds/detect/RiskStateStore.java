package com.bank.fds.detect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 고객 위험 상태와 단기 거래 카운터.
 *
 * <p><b>사후가 사전을 먹여 준다.</b> 무거운 분석은 사후 탐지가 하고, 그 결과를 여기에
 * 남겨 두면 사전 점검은 <b>조회만</b> 하고 끝난다. 인라인 경로는 예산이 수백 ms 라
 * 그 자리에서 계산할 수 없다 — 실무 FDS 가 프로파일을 미리 만들어 두는 이유다.
 *
 * <p>Redis 를 쓰는 이유는 두 값 다 TTL 이 있기 때문이다. 위험 상태는 영구 낙인이
 * 아니라 한동안 조심하자는 표시이고, 속도 카운터는 애초에 시간창 개념이다.
 *
 * <p><b>장애 시 판정을 막지 않는다.</b> 조회에 실패하면 비어 있는 것으로 보고
 * {@code degraded} 로 표시해 넘긴다. Redis 가 죽었다고 결제 승인을 세우면
 * 그게 더 큰 사고다 — 대신 축소 판정이었다는 사실은 응답에 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskStateStore {

    private static final String RISK_PREFIX = "fds:risk:";
    private static final String VELOCITY_PREFIX = "fds:vel:";
    private static final String RECEIVER_PREFIX = "fds:rcv:";

    private final StringRedisTemplate redis;

    @Value("${fds.risk.elevated-ttl-hours:24}")
    private long elevatedTtlHours;

    @Value("${fds.velocity.window-minutes:10}")
    private long velocityWindowMinutes;

    /** 수취인 이력 보관 기간. 너무 짧으면 단골 수취인도 매번 신규로 잡힌다. */
    @Value("${fds.receiver.history-days:180}")
    private long receiverHistoryDays;

    /**
     * 사후 탐지가 올려 둔 위험 상태. 없으면 empty.
     *
     * <p>조회 실패도 empty 다 — 호출부가 {@link #isAvailable()} 로 구분한다.
     */
    public Optional<ResponseTier> elevatedTier(String customerId) {
        try {
            String raw = redis.opsForValue().get(RISK_PREFIX + customerId);
            return raw == null ? Optional.empty() : Optional.of(ResponseTier.valueOf(raw));
        } catch (IllegalArgumentException e) {
            // 저장된 값이 현재 enum 에 없다. 배포 중 등급 이름이 바뀌면 생길 수 있다.
            log.warn("알 수 없는 위험 등급 값 customerId={} reason={}", customerId, e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("위험 상태 조회 실패 customerId={} reason={}", customerId, e.toString());
            return Optional.empty();
        }
    }

    /** 사후 탐지가 이 고객을 한동안 조심하도록 표시한다. */
    public void elevate(String customerId, ResponseTier tier) {
        try {
            redis.opsForValue().set(
                    RISK_PREFIX + customerId, tier.name(), Duration.ofHours(elevatedTtlHours));
        } catch (Exception e) {
            log.warn("위험 상태 기록 실패 customerId={} reason={}", customerId, e.toString());
        }
    }

    /**
     * 시간창 안의 거래 시도 횟수를 1 올리고 현재 값을 준다.
     *
     * <p>사전 점검마다 부른다 — 승인되지 않은 시도도 속도에는 의미가 있다.
     * 실패한 시도가 반복되는 것 자체가 신호이기 때문이다.
     *
     * @return 조회·기록에 실패하면 empty
     */
    public Optional<Long> touchVelocity(String customerId) {
        try {
            String key = VELOCITY_PREFIX + customerId;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 첫 증가에만 TTL 을 건다. 매번 걸면 창이 계속 밀려 영원히 안 닫힌다.
                redis.expire(key, Duration.ofMinutes(velocityWindowMinutes));
            }
            return Optional.ofNullable(count);
        } catch (Exception e) {
            log.warn("속도 카운터 갱신 실패 customerId={} reason={}", customerId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 이 고객이 이 수취인에게 처음 보내는가. 확인과 동시에 기록한다.
     *
     * <p>대포통장으로 흘러가는 자금은 대개 처음 보는 계좌로 간다. 다만 신규 수취인
     * 자체는 지극히 정상이라(이사, 첫 거래처) 단독으로는 약한 신호다 —
     * 금액이나 시간대와 겹칠 때 의미가 생긴다.
     *
     * <p>기록에 실패하면 "처음이 아니다" 로 본다. 판정 근거를 만들지 못한 것을
     * 신호로 바꾸면, Redis 장애가 곧 오탐 폭증이 된다.
     */
    public boolean isFirstTimeReceiver(String customerId, String receiverKey) {
        if (customerId == null || receiverKey == null || receiverKey.isBlank()) {
            return false;
        }
        try {
            String key = RECEIVER_PREFIX + customerId;
            Long added = redis.opsForSet().add(key, receiverKey);
            redis.expire(key, Duration.ofDays(receiverHistoryDays));
            return added != null && added == 1L;
        } catch (Exception e) {
            log.warn("수취인 이력 확인 실패 customerId={} reason={}", customerId, e.toString());
            return false;
        }
    }

    /** Redis 가 응답하는가. 축소 판정 여부를 가리는 데 쓴다. */
    public boolean isAvailable() {
        try {
            redis.hasKey(RISK_PREFIX + "__probe__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
