package com.bank.loan.advisory.listener;

import com.bank.loan.advisory.agent.AuditFairnessAgent;
import com.bank.loan.advisory.engine.AdvisoryEvaluator;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.notification.config.AsyncConfig;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.event.LoanReviewCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 본심사 완료 이벤트 수신 → SYNC 어드바이저리 룰 평가 + 감사/공정성 Agent 호출.
 *
 * AUTO 심사는 배치(일배치) 에서 다루므로 MANUAL 심사만 처리한다.
 * 실패해도 본심사 결과에 영향 없음 — DiscardPolicy(큐 가득) + 예외 격리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryReviewListener {

    private final AdvisoryEvaluator    evaluator;
    private final AuditFairnessAgent   auditFairnessAgent;

    @Async(AsyncConfig.ADVISORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(LoanReviewCompletedEvent event) {
        if (!LoanReview.TYPE_MANUAL.equals(event.revTypeCd())) {
            return;
        }
        try {
            List<Long> advrIds = evaluator.evaluate(RuleContext.sync(event.revId()));
            if (!advrIds.isEmpty()) {
                auditFairnessAgent.analyzeReports(advrIds);
            }
            log.info("SYNC 어드바이저리 완료 — revId={} decision={} signals={}",
                    event.revId(), event.revDecisionCd(), advrIds.size());
        } catch (Exception e) {
            log.warn("SYNC 어드바이저리 실패 (무시) — revId={}: {}", event.revId(), e.getMessage());
        }
    }
}
