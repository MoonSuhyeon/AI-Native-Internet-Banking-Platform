package com.bank.loan.advisory.listener;

import com.bank.loan.advisory.event.AdvisoryReportPublishedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 어드바이저리 리포트 발행 이벤트 수신 → 로그 기록.
 *
 * AdvisoryBatchEvaluationService(@Transactional) 안에서 발행되므로
 * AFTER_COMMIT 후 호출된다.
 * 실패해도 배치 결과에 영향 없음 — 예외 격리.
 */
@Slf4j
@Component
public class AdvisoryReportPublishedListener {

    public static final String EVENT_TYPE = "REPORT_PUBLISHED";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportPublished(AdvisoryReportPublishedEvent event) {
        log.info("[report-published] advrId={} reviewer={} severity={} baseDate={}",
                event.advrId(), event.targetReviewerId(), event.severityCd(), event.baseDate());
    }
}
