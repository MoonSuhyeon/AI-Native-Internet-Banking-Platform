package com.bank.loan.advisory.engine;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.rag.PolicyCitationRetriever;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 어드바이저리 룰 엔진 오케스트레이터. 등록된 모든 {@link AdvisoryRule} 빈을 mode 별로 분기해 호출하고,
 * 결과 {@link RuleResult} 를 `REVIEW_ADVISORY_REPORT` + `REVIEW_ADVISORY_SIGNAL` 로 영속화한다.
 *
 * 격리 정책:
 *   - 룰이 `REVIEW_ADVISORY_RULE` 마스터에 등록되지 않았거나 active_yn='N' 이면 skip (no-op).
 *   - 룰 evaluate 가 예외를 던지면 해당 룰만 격리하고 다른 룰은 계속 진행 (본심사 자체는 살린다).
 *   - 영속화(INSERT) 실패는 트랜잭션 무결성을 위해 throw — 호출측이 처리.
 *
 * 호출 진입점은 {@link #evaluate(RuleContext)} 단일. 호출측은 보통
 * {@code evaluate(RuleContext.sync(revId))} 또는 {@code evaluate(RuleContext.batch(date))} 를 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryEvaluator {

    private final List<AdvisoryRule> rules;
    private final ReviewAdvisoryRuleRepository   ruleMasterRepo;
    private final ReviewAdvisoryReportRepository reportRepo;
    private final ReviewAdvisorySignalRepository signalRepo;
    private final AdvisoryMetrics                metrics;
    private final PolicyCitationRetriever        citationRetriever;
    private final ObjectMapper                   objectMapper;

    @Transactional
    public List<Long> evaluate(RuleContext context) {
        Timer.Sample timer = metrics.startEvaluateTimer();
        try {
            return doEvaluate(context);
        } finally {
            metrics.recordEvaluateDuration(timer, context.mode().name());
        }
    }

    private List<Long> doEvaluate(RuleContext context) {
        List<Long> publishedAdvrIds = new ArrayList<>();
        if (rules.isEmpty()) {
            return publishedAdvrIds;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (AdvisoryRule rule : rules) {
            if (!rule.supports(context.mode())) continue;

            ReviewAdvisoryRule master = ruleMasterRepo
                    .findByRuleCdAndDeletedAtIsNull(rule.ruleCd())
                    .orElse(null);
            if (master == null || !master.isActive()) {
                log.debug("어드바이저리 룰 비활성/미등록 — skip ruleCd={}", rule.ruleCd());
                continue;
            }

            List<RuleResult> results;
            try {
                results = rule.evaluate(context);
            } catch (Exception e) {
                log.warn("어드바이저리 룰 평가 실패 — ruleCd={}, mode={}, revId={}",
                        rule.ruleCd(), context.mode(), context.revId(), e);
                continue;
            }
            if (results == null || results.isEmpty()) continue;

            for (RuleResult result : results) {
                Long advrId = persist(master, result, now);
                publishedAdvrIds.add(advrId);
                metrics.incrementPublished(rule.ruleCd(), result.severityCd());
            }
        }
        return publishedAdvrIds;
    }

    private Long persist(ReviewAdvisoryRule master, RuleResult result, OffsetDateTime generatedAt) {
        ReviewAdvisoryReport report = reportRepo.save(ReviewAdvisoryReport.builder()
                .revId(result.revId())
                .ruleId(master.getRuleId())
                .advisoryTypeCd(result.advisoryTypeCd())
                .severityCd(result.severityCd())
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle(result.advrTitle())
                .advrSummary(result.advrSummary())
                .advrPayload(result.advrPayloadJson())
                .targetReviewerId(result.targetReviewerId())
                .generatedAt(generatedAt)
                .build());

        List<SignalSpec> signals = result.signals();
        if (signals != null) {
            for (SignalSpec spec : signals) {
                signalRepo.save(ReviewAdvisorySignal.builder()
                        .advrId(report.getAdvrId())
                        .signalKindCd(spec.signalKindCd())
                        .signalMetric(spec.signalMetric())
                        .observedValue(spec.observedValue())
                        .thresholdValue(spec.thresholdValue())
                        .peerBaselineValue(spec.peerBaselineValue())
                        .sampleSize(spec.sampleSize())
                        .signalDetail(spec.signalDetailJson())
                        .observedWindowStart(spec.observedWindowStart())
                        .observedWindowEnd(spec.observedWindowEnd())
                        .observedAt(spec.observedAt() != null ? spec.observedAt() : generatedAt)
                        .build());
            }
        }
        // 6-7: CRITICAL 룰 발화 시 정책 인용 자동 적재 (best-effort — 실패해도 리포트 무결성 유지)
        if (ReviewAdvisoryReport.SEVERITY_CRITICAL.equals(master.getSeverityCd())) {
            attachCitations(report, master, result);
        }

        return report.getAdvrId();
    }

    /**
     * CRITICAL 리포트에 정책 인용(PolicyCitation) 을 자동 첨부한다 (plan §11.4.2 — Task 6-7).
     * 실패해도 리포트 자체는 유지 — 경고 로그만 남기고 조용히 종료.
     */
    private void attachCitations(ReviewAdvisoryReport report,
                                 ReviewAdvisoryRule master,
                                 RuleResult result) {
        try {
            String queryText = result.advrSummary() != null ? result.advrSummary()
                    : master.getRuleCd() + " " + master.getRuleDesc();
            PolicyCitationResponse citation = citationRetriever.retrieve(
                    report.getAdvrId(), master.getRuleCd(), queryText, 0L);
            if (!citation.citations().isEmpty()) {
                String citationsJson = objectMapper.writeValueAsString(citation.citations());
                // advr_payload 에 citations 병합
                String merged = mergeCitationsIntoPayload(report.getAdvrPayload(), citationsJson);
                report.updatePayload(merged);
                log.debug("CRITICAL 정책 인용 첨부 — advrId={} citationCount={}",
                        report.getAdvrId(), citation.totalCount());
            }
        } catch (Exception e) {
            log.warn("CRITICAL 정책 인용 첨부 실패 (무시) — advrId={}: {}", report.getAdvrId(), e.getMessage());
        }
    }

    private String mergeCitationsIntoPayload(String existingPayload, String citationsJson) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = existingPayload != null
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(existingPayload)
                    : objectMapper.createObjectNode();
            node.set("citations", objectMapper.readTree(citationsJson));
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // payload 파싱 실패 시 기존 payload 유지
            return existingPayload;
        }
    }
}
