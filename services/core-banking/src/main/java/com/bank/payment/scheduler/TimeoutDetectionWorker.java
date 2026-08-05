package com.bank.payment.scheduler;

import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F6 시나리오 A: KFTC/BOK 청산 타임아웃 감지 폴링워커.
 *
 * next_timeout_at < now AND status='CLEARING' 인 PI = ACK/완결 미수신 타임아웃.
 * 처리: 상태이력(CLEARING→CLEARING, {KFTC|BOK}_TIMEOUT_DETECTED) + next_timeout_at NULL 클리어.
 * routing_network_type으로 이벤트명/reasonCode 분기 (TimeoutTransactionHelper 위임).
 * ★ 보상/역분개/PI 상태전이 절대 없음. 운영자 알림(로그)만.
 * ★ @Transactional 없음 — DB 쓰기는 TimeoutTransactionHelper에 위임(OutboxPublisher 격리 패턴).
 */
@Slf4j
/**
 * 결제계 백그라운드 워커.
 *
 * <p>{@code payment.scheduler.enabled=false} 로 끌 수 있다. 병합 이후 수신계 슬라이스
 * 테스트가 같은 애플리케이션 정의를 공유하게 되면서, 이 워커들이 함께 떠서 존재하지 않는
 * 결제 테이블(H2)을 주기적으로 조회하다 컨텍스트를 깨뜨렸다. 도메인별 가벼운 테스트가
 * 상대 도메인의 배치까지 짊어지지 않도록 스위치를 둔다. 기본값은 켜짐이다.
 */
@ConditionalOnProperty(name = "payment.scheduler.enabled", matchIfMissing = true)
@Component
public class TimeoutDetectionWorker {

    private final PaymentInstructionMapper paymentInstructionMapper;
    private final TimeoutTransactionHelper timeoutHelper;

    public TimeoutDetectionWorker(
            PaymentInstructionMapper paymentInstructionMapper,
            TimeoutTransactionHelper timeoutHelper) {
        this.paymentInstructionMapper = paymentInstructionMapper;
        this.timeoutHelper = timeoutHelper;
    }

    @Scheduled(fixedDelayString = "${payment.timeout.poll-interval-ms:10000}")
    public void detectTimedOut() {
        List<PaymentInstruction> timedOut = paymentInstructionMapper.selectTimedOut();
        if (timedOut.isEmpty()) {
            return;
        }
        for (PaymentInstruction pi : timedOut) {
            try {
                timeoutHelper.markTimeoutDetected(pi);
                log.warn("[F6] ★운영자 알림★ 청산 타임아웃 감지. piId={} routing={} status=CLEARING",
                        pi.getPaymentInstructionId(), pi.getRoutingNetworkType());
            } catch (Exception e) {
                // 1건 실패가 나머지 막지 않게 격리. next_timeout_at 클리어 안 됐으면 다음 폴링에서 재시도.
                log.error("[F6] 타임아웃 처리 실패. piId={}", pi.getPaymentInstructionId(), e);
            }
        }
    }
}
