package com.bank.harness.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * 재시도 실행기.
 *
 * <p>에이전트마다 for 문으로 흩어져 있던 재시도를 한 자리에 모은 것이다.
 * 흩어져 있으면 정책이 조금씩 달라지고(고정 대기 vs 선형 증가), 무엇보다
 * <b>재시도 여부 판단이 없어</b> 다시 해도 소용없는 실패까지 반복하게 된다.
 */
@Slf4j
public final class RetryExecutor {

    private final RetryPolicy policy;

    /**
     * 재시도를 계속해도 되는지 묻는 관문.
     *
     * <p>비용 상한이 대표적이다. 예산을 다 쓴 상태에서 재시도하면 실패를 비싸게 반복할 뿐이다.
     * 기존 구현에는 이 연결이 없어서 재시도와 비용이 서로를 몰랐다.
     */
    private final BooleanSupplier budgetGate;

    public RetryExecutor(RetryPolicy policy) {
        this(policy, () -> true);
    }

    public RetryExecutor(RetryPolicy policy, BooleanSupplier budgetGate) {
        this.policy = policy;
        this.budgetGate = budgetGate;
    }

    /**
     * 실패 시 정책에 따라 다시 시도한다.
     *
     * @param label 로그·추적에 쓰는 이름
     * @throws Exception 마지막 시도의 예외를 그대로 올린다. 재시도했다는 사실이
     *                   원래 실패 원인을 가리면 안 되기 때문이다.
     */
    public <T> T execute(String label, Callable<T> work) throws Exception {
        Throwable last = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return work.call();
            } catch (Exception e) {
                last = e;

                boolean lastAttempt = attempt == policy.maxAttempts();
                if (lastAttempt) {
                    throw e;
                }
                if (!policy.retryable().test(e)) {
                    log.debug("[retry] {} — 재시도 대상이 아님: {}", label, e.toString());
                    throw e;
                }
                if (!budgetGate.getAsBoolean()) {
                    log.warn("[retry] {} — 예산 소진으로 재시도 중단 (attempt={})", label, attempt);
                    throw e;
                }

                var wait = policy.backoffFor(attempt);
                log.warn("[retry] {} 실패 attempt={}/{} — {}ms 후 재시도: {}",
                        label, attempt, policy.maxAttempts(), wait.toMillis(), e.toString());
                sleep(wait.toMillis());
            }
        }
        // 도달하지 않는다. 마지막 시도는 위에서 throw 된다.
        throw new IllegalStateException("재시도 루프 이탈 — " + label, last);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            // 인터럽트를 삼키면 상위에서 중단 요청을 알 수 없다.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", ie);
        }
    }
}
