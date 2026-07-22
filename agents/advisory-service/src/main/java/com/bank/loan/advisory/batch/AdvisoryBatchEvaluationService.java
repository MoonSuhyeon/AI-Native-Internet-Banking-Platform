package com.bank.loan.advisory.batch;

import com.bank.loan.advisory.agent.AuditFairnessAgent;
import com.bank.loan.advisory.batch.ReviewerDecisionSnapshotService.SnapshotRunResult;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.engine.AdvisoryEvaluator;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.event.AdvisoryReportPublishedEvent;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 일배치 평가 — 스냅샷 적재 + 모든 BATCH 룰 일괄 평가 + 발행 이벤트 통지.
 * 운영자가 수동 트리거하거나 외부 스케줄러(cron 등)에서 호출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryBatchEvaluationService {

    private final ReviewerDecisionSnapshotService snapshotService;
    private final AdvisoryEvaluator evaluator;
    private final AuditFairnessAgent auditFairnessAgent;
    private final ReviewAdvisoryReportRepository reportRepo;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public BatchEvaluationResult runDailyBatch(String baseDate) {
        SnapshotRunResult snap = snapshotService.runDailySnapshot(baseDate);
        List<Long> advrIds = evaluator.evaluate(RuleContext.batch(baseDate));

        auditFairnessAgent.analyzeReports(advrIds);

        for (Long advrId : advrIds) {
            reportRepo.findById(advrId).ifPresent(r ->
                    eventPublisher.publishEvent(new AdvisoryReportPublishedEvent(
                            r.getAdvrId(), r.getTargetReviewerId(), r.getSeverityCd(), baseDate
                    )));
        }
        log.info("어드바이저리 배치 평가 완료 baseDate={} snapshotInserted={} reportsPublished={}",
                baseDate, snap.inserted(), advrIds.size());

        return BatchEvaluationResult.builder()
                .baseDate(baseDate)
                .snapshot(snap)
                .reportsPublished(advrIds.size())
                .build();
    }

    @Builder
    public record BatchEvaluationResult(String baseDate, SnapshotRunResult snapshot, int reportsPublished) {}
}
