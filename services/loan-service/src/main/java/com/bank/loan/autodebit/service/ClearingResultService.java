package com.bank.loan.autodebit.service;

import com.bank.loan.autodebit.domain.AutoDebitClearingPending;
import com.bank.loan.autodebit.repository.AutoDebitClearingPendingRepository;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.service.RepaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 자동이체 타행 청산(CLEARING) 결과 처리.
 *
 * payment.completed / payment.failed 이벤트의 piId 로 청산 대기건을 찾아
 * 상환을 완결(COMPLETED)하거나 실패(FAILED)로 기록한다.
 * 동일 piId 중복 이벤트는 대기건 status(PENDING) 가드 + RepaymentService 멱등키로 차단된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClearingResultService {

    /** 원장 기록용 채널 (RepaymentTransaction.channel_cd) — 자동이체 식별값 */
    // 원장(repayment_transaction.channel_cd) 자동이체 식별값.
    // 회계 요약 집계(AccountingSummaryQuery)와 시드(V39)가 AUTO_DEBIT 을 정본으로 쓴다.
    // 과거 INBOUND 로 적재하던 탓에 일일·월별 회계 요약의 자동이체 건수가 0 이었다.
    // payment-service 호출 채널(OPEN_BANKING)과는 별개 필드다.
    private static final String CHANNEL_AUTO_DEBIT = "AUTO_DEBIT";

    private final AutoDebitClearingPendingRepository pendingRepository;
    private final RepaymentService repaymentService;

    @Transactional
    public void handle(String piId, boolean completed) {
        Optional<AutoDebitClearingPending> found = pendingRepository.findByPiId(piId);
        if (found.isEmpty()) {
            // 자동이체 청산 대기건이 아님 (대출실행/온라인상환 등) → 무시
            log.debug("clearing result: 대기건 없음 piId={}", piId);
            return;
        }

        AutoDebitClearingPending pending = found.get();
        if (!pending.isPending()) {
            log.info("clearing result: 이미 해소된 대기건 piId={} status={}", piId, pending.getStatus());
            return;
        }

        String paymentStatus = completed ? null : RepaymentTransaction.STATUS_FAILED;
        RepayInstallmentRequest req = new RepayInstallmentRequest(
                pending.getInstallmentNo(), CHANNEL_AUTO_DEBIT, pending.getBaseDate());

        // FAILED 기록은 배치 멱등키(AUTO-{cntr}-{rsch}-{baseDate})를 그대로 쓰면 같은 baseDate
        // 재수납 키와 충돌해 당일 재수납이 막힌다. 별도 -FAIL 네임스페이스로 분리한다.
        String ledgerIdemKey = completed
                ? pending.getIdempotencyKey()
                : pending.getIdempotencyKey() + "-FAIL";

        log.info("clearing result: piId={} completed={} cntrId={} rschId={}",
                piId, completed, pending.getCntrId(), pending.getRschId());

        repaymentService.repayInstallment(pending.getCntrId(), req,
                ledgerIdemKey, paymentStatus, piId);

        pending.resolve(completed);
    }
}
