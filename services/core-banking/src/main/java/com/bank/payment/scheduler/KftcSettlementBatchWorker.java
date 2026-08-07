package com.bank.payment.scheduler;

import com.bank.common.time.BusinessDate;
import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.mapper.KftcClearingTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KFTC 차액결제 마감 배치 (익영업일 11시 모사).
 *
 * 당일 SETTLEMENT_NOTIFY로 CT SETTLED + PI CLEARING 상태인 건 전체를
 * 한은당좌 unwind 분개 + PI CLEARING→COMPLETED로 일괄 처리.
 * ★ @Transactional 없음 — DB 쓰기는 KftcSettlementHelper(@Transactional)에 위임.
 *    1건 실패가 나머지 막지 않도록 예외 격리 (TimeoutDetectionWorker 패턴).
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
@RequiredArgsConstructor
public class KftcSettlementBatchWorker {

    private final KftcClearingTransactionMapper ctMapper;
    private final KftcSettlementHelper settlementHelper;
    private final PaymentMetrics metrics;

    @Scheduled(cron = "${payment.settlement.kftc-cutoff-cron:0 0 11 * * *}", zone = "Asia/Seoul")
    public void runDailySettlement() {
        String today = BusinessDate.today();  // yyyyMMdd (KST 고정)
        List<KftcClearingTransaction> dueList = ctMapper.selectDueForSettlement(today);

        if (dueList.isEmpty()) {
            log.info("[KFTC마감] 당일 정산 대상 없음. settlementDate={}", today);
            return;
        }

        Long totalAmount = dueList.stream()
                .map(KftcClearingTransaction::getClearingAmount)
                .reduce(0L, Long::sum);
        log.info("[KFTC마감] 정산 시작. settlementDate={} 건수={} 총액={}", today, dueList.size(), totalAmount);

        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        for (KftcClearingTransaction ct : dueList) {
            try {
                settlementHelper.settleKftc(ct);
                log.info("[KFTC마감] 정산완료. piId={} clearingNo={} amount={}",
                        ct.getOurPaymentInstructionId(), ct.getClearingNo(), ct.getClearingAmount());
                successCount++;
            } catch (OptimisticLockingFailureException e) {
                // 다중 인스턴스 경합 패배 — 다른 인스턴스가 이미 정산함. 실패 아님.
                log.info("[KFTC마감] 정산 경합 skip(다른 인스턴스 처리). piId={} clearingNo={}",
                        ct.getOurPaymentInstructionId(), ct.getClearingNo());
                skipCount++;
            } catch (Exception e) {
                log.error("[KFTC마감] 정산실패 — 건 격리 후 계속. piId={} clearingNo={}",
                        ct.getOurPaymentInstructionId(), ct.getClearingNo(), e);
                failCount++;
                metrics.kftcSettlementFailed();
            }
        }
        if (failCount > 0) {
            log.error("[KFTC마감] 정산 종료 — 실패 건 존재. settlementDate={} 성공={} skip={} 실패={} "
                    + "(실패 건은 익일 이후 배치에서 재시도됨)", today, successCount, skipCount, failCount);
        } else {
            log.info("[KFTC마감] 정산 종료. settlementDate={} 성공={} skip={} 실패={}",
                    today, successCount, skipCount, failCount);
        }
    }
}
