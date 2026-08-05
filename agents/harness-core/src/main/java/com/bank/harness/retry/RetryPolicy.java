package com.bank.harness.retry;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * LLM 호출 재시도 정책.
 *
 * <p>{@code AgentLoopGuard} 와 혼동하기 쉬운데 축이 다르다.
 * <ul>
 *   <li>루프 가드 — <b>한 실행 안에서</b> 툴·LLM 을 몇 번까지 부를 수 있는가 (폭주 방지)</li>
 *   <li>재시도 정책 — <b>한 번의 호출이</b> 실패했을 때 몇 번까지 다시 시도하는가 (복구)</li>
 * </ul>
 * 둘 다 필요하다. 기존에는 루프 가드만 있었고, 재시도는 에이전트마다 for 문으로
 * 손수 짜여 있었다(review-ai-gateway 에 두 벌).
 *
 * @param maxAttempts    최초 시도를 포함한 총 시도 횟수. 1이면 재시도 없음.
 * @param initialBackoff 첫 재시도 전 대기
 * @param multiplier     회차마다 대기를 곱할 배수. 1.0 이면 고정 간격.
 * @param maxBackoff     대기 상한. 지수 증가가 무한정 커지지 않게 한다.
 * @param retryable      다시 시도할 가치가 있는 실패인지 판단.
 *                       <b>이 술어가 정책의 핵심이다.</b> 잔액 부족·검증 실패처럼
 *                       다시 해도 같은 결과인 실패를 재시도하면 비용만 늘고
 *                       사용자 대기만 길어진다.
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        double multiplier,
        Duration maxBackoff,
        Predicate<Throwable> retryable
) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 는 1 이상이어야 한다: " + maxAttempts);
        }
        if (initialBackoff == null) initialBackoff = Duration.ofMillis(500);
        if (maxBackoff == null) maxBackoff = Duration.ofSeconds(10);
        if (multiplier < 1.0) multiplier = 1.0;
        if (retryable == null) retryable = t -> true;
    }

    /** n회차(1-based) 재시도 전 대기 시간. */
    public Duration backoffFor(int attempt) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis(Math.min((long) millis, maxBackoff.toMillis()));
    }

    /** 재시도 없이 한 번만 시도한다. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, t -> false);
    }
}
