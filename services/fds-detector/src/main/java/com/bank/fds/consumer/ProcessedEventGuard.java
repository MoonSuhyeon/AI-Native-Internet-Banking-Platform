package com.bank.fds.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 같은 거래를 두 번 세지 않게 막는다.
 *
 * <p><b>왜 필요한가.</b> Kafka 는 at-least-once 다. 리밸런싱이나 재처리로 같은 이벤트가
 * 다시 온다. 속도·분할·fan-out 룰은 <b>건수를 세는</b> 룰이라, 중복이 그대로 들어오면
 * 없던 이상거래가 생긴다 — 예외도 안 나고 로그도 안 남는 오탐이다.
 *
 * <p><b>왜 Redis 인가.</b> TTL 이 있는 휘발성 데이터라 DB 와 마이그레이션을 둘 이유가 없다.
 * 재처리를 막아야 하는 기간은 길지 않다.
 *
 * <p><b>한계를 분명히 한다.</b> Redis 가 비면 중복 방지가 풀린다. 그 경우 통계가
 * 부풀 뿐 결제나 조사에는 영향이 없고, 사건은 사람 검토를 거친다. 결제 정합성을
 * 지키는 멱등(결제계 idempotency_key)과는 별개의, 통계 보호용 장치다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventGuard {

    private static final String KEY_PREFIX = "fds:seen:";

    private final StringRedisTemplate redis;

    @Value("${fds.dedupe.ttl-minutes:180}")
    private long ttlMinutes;

    /**
     * 처음 보는 거래면 true, 이미 처리한 거래면 false.
     *
     * <p>Redis 가 죽으면 true 를 준다 — 중복 방지가 풀릴지언정 탐지는 계속 돌아야 한다.
     * 막지 못한 중복은 통계를 부풀리지만, 탐지가 멈추면 그동안의 거래를 통째로 놓친다.
     */
    public boolean markIfFirstSeen(String paymentInstructionId) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(
                    KEY_PREFIX + paymentInstructionId, "1", Duration.ofMinutes(ttlMinutes));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("중복 방지 확인 실패 — 처리 계속 piId={} reason={}", paymentInstructionId, e.toString());
            return true;
        }
    }
}
