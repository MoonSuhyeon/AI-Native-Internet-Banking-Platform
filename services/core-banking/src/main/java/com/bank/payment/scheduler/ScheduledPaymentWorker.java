package com.bank.payment.scheduler;

import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예약이체 실행 폴링워커.
 *
 * scheduled_execution_at < now AND status='SCHEDULED' 인 PI = 실행 시각 도래한 예약이체.
 * 단계 2: claim(SCHEDULED→PROCESSING 선점).
 * 단계 3: executeScheduledIntraBank(claim 성공 pi) — 자행 완결 + 실패/보상 처리.
 *         내부에서 PaymentValidationException/DepositInboundFailureException/LedgerInsertFailureException
 *         모두 종료상태(FAILED)로 닫아 PROCESSING stuck 을 방지.
 * ★ @Transactional 없음 — claim(독립 TX)과 execute(별도 TX) 분리.
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
public class ScheduledPaymentWorker {

    private final PaymentInstructionMapper paymentInstructionMapper;
    private final PaymentTransactionService txService;
    private final PaymentOrchestrator orchestrator;

    public ScheduledPaymentWorker(PaymentInstructionMapper paymentInstructionMapper,
                                  PaymentTransactionService txService,
                                  PaymentOrchestrator orchestrator) {
        this.paymentInstructionMapper = paymentInstructionMapper;
        this.txService = txService;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${payment.scheduled.poll-interval-ms:30000}")
    public void triggerDueScheduled() {
        List<PaymentInstruction> due = paymentInstructionMapper.selectDueScheduled();
        if (due.isEmpty()) {
            return;
        }
        for (PaymentInstruction pi : due) {
            try {
                boolean claimed = txService.claimScheduled(pi);
                if (!claimed) {
                    log.info("[SCHED] 이미 선점됨 skip piId={}", pi.getPaymentInstructionId());
                    continue;
                }
                log.info("[SCHED] claim 성공 piId={}", pi.getPaymentInstructionId());
                orchestrator.executeScheduledIntraBank(pi);
                log.info("[SCHED] 실행 완료 piId={}", pi.getPaymentInstructionId());
            } catch (Exception e) {
                // executeScheduledIntraBank 내부에서 정상 실패는 종료상태(FAILED)로 닫힘.
                // 여기까지 올라온 예외는 보상 자체 실패 등 예상외 예외 — REVERSING 잔류(범위 밖).
                log.error("[SCHED] 예상외 처리 실패 piId={} — 운영자 확인 필요",
                        pi.getPaymentInstructionId(), e);
            }
        }
    }
}
