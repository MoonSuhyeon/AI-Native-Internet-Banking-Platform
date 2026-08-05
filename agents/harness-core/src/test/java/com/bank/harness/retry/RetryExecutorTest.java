package com.bank.harness.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryExecutorTest {

    private static RetryPolicy policy(int attempts, java.util.function.Predicate<Throwable> retryable) {
        return new RetryPolicy(attempts, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), retryable);
    }

    @Test
    @DisplayName("성공하면 한 번만 부른다")
    void 성공_단일호출() throws Exception {
        var calls = new AtomicInteger();
        var result = new RetryExecutor(policy(3, t -> true))
                .execute("ok", () -> { calls.incrementAndGet(); return "결과"; });

        assertThat(result).isEqualTo("결과");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("일시 실패는 재시도해서 회복한다")
    void 재시도_회복() throws Exception {
        var calls = new AtomicInteger();
        var result = new RetryExecutor(policy(3, t -> true)).execute("flaky", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("일시 장애");
            }
            return "회복";
        });

        assertThat(result).isEqualTo("회복");
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("마지막 시도의 예외를 그대로 올린다 — 재시도가 원인을 가리면 안 된다")
    void 최종_실패시_원래예외() {
        var executor = new RetryExecutor(policy(2, t -> true));

        assertThatThrownBy(() -> executor.execute("always-fail", () -> {
            throw new IOException("모델 응답 없음");
        }))
                .isInstanceOf(IOException.class)
                .hasMessage("모델 응답 없음");
    }

    @Test
    @DisplayName("재시도 대상이 아니면 즉시 포기한다 — 같은 결과를 비싸게 반복하지 않는다")
    void 비재시도_예외는_즉시중단() {
        var calls = new AtomicInteger();
        // 검증 실패는 다시 해도 같은 결과다.
        var executor = new RetryExecutor(policy(5, t -> !(t instanceof IllegalArgumentException)));

        assertThatThrownBy(() -> executor.execute("validation", () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("스키마 불일치");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).as("한 번만 시도하고 멈춘다").hasValue(1);
    }

    @Test
    @DisplayName("예산이 소진되면 재시도를 멈춘다 — 실패를 비싸게 반복하지 않는다")
    void 예산_소진시_중단() {
        var calls = new AtomicInteger();
        var executor = new RetryExecutor(policy(5, t -> true), () -> false);

        assertThatThrownBy(() -> executor.execute("costly", () -> {
            calls.incrementAndGet();
            throw new IOException("장애");
        })).isInstanceOf(IOException.class);

        assertThat(calls).as("예산 관문이 막아 재시도하지 않는다").hasValue(1);
    }

    @Test
    @DisplayName("대기가 지수로 늘되 상한을 넘지 않는다")
    void 백오프_상한() {
        var p = new RetryPolicy(10, Duration.ofMillis(100), 2.0, Duration.ofMillis(300), t -> true);

        assertThat(p.backoffFor(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(p.backoffFor(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(p.backoffFor(3)).as("400ms 가 아니라 상한 300ms").isEqualTo(Duration.ofMillis(300));
        assertThat(p.backoffFor(9)).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("none() 은 재시도하지 않는다")
    void 재시도_없음() {
        var calls = new AtomicInteger();
        var executor = new RetryExecutor(RetryPolicy.none());

        assertThatThrownBy(() -> executor.execute("once", () -> {
            calls.incrementAndGet();
            throw new IOException("장애");
        })).isInstanceOf(IOException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("maxAttempts 가 1 미만이면 만들 수 없다")
    void 잘못된_설정_거부() {
        assertThatThrownBy(() -> new RetryPolicy(0, null, 1.0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }
}
