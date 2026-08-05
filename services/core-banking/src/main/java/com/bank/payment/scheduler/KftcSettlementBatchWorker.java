package com.bank.payment.scheduler;

import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.mapper.KftcClearingTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
@Component
@RequiredArgsConstructor
public class KftcSettlementBatchWorker {

    private final KftcClearingTransactionMapper ctMapper;
    private final KftcSettlementHelper settlementHelper;
    private final PaymentMetrics metrics;

    @Scheduled(cron = "${payment.settlement.kftc-cutoff-cron:0 0 11 * * *}")
    public void runDailySettlement() {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);  // yyyyMMdd (KST 고정)
        List<KftcClearingTransaction> dueList = ctMapper.selectDueForSettlement(today);

        if (dueList.isEmpty()) {
            log.info("[KFTC마감] 당일 정산 대상 없음. settlementDate={}", today);
            return;
        }

        BigDecimal totalAmount = dueList.stream()
                .map(KftcClearingTransaction::getClearingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
