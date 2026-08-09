package com.bank.loan.advisory.agent;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import com.bank.loan.advisory.domain.audit.AiAuditOpinion;
import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import com.bank.loan.advisory.dto.PolicyCitationResponse.CitationItem;
import com.bank.loan.advisory.event.QuarantineTriggeredEvent;
import com.bank.loan.advisory.gateway.AiGatewayClient;
import com.bank.loan.advisory.gateway.AiGatewayProperties;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest.GatewayRagChunk;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest.GatewaySignalSummary;
import com.bank.loan.advisory.gateway.GatewayAnalysisResponse;
import com.bank.loan.advisory.rag.PiiMaskingUtil;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.bank.loan.advisory.repository.audit.AiAuditOpinionRepository;
import com.bank.loan.advisory.repository.audit.ReviewerRiskScoreRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 감사/공정성 Agent. 룰 엔진이 WARN 이상 리포트를 발행한 뒤 호출.
 *
 * 처리 순서:
 *   1. 리포트·신호 로드 → advisoryTypeCd 기준으로 BIAS / COMPLIANCE 그룹 분류
 *      - "BIAS_DETECTION"    → BIAS_DETECTION 분석
 *      - 그 외 (REREVIEW_RECOMMEND 등) → COMPLIANCE_VERIFICATION 분석
 *   2. 심사관 의견서(revRemark) 로드 + PII 마스킹
 *   3. advrPayload.citations 에서 RAG 청크 추출 (CRITICAL 리포트에 저장된 것)
 *   4. review-ai-gateway 호출 (best-effort — 실패 시 경고만)
 *   5. AiAuditOpinion 저장 + ReviewerRiskScore UPSERT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditFairnessAgent {

    static final String ADVISORY_TYPE_BIAS = "BIAS_DETECTION";

    private final ReviewAdvisoryReportRepository  reportRepo;
    private final ReviewAdvisorySignalRepository  signalRepo;
    private final LoanReviewRepository            loanReviewRepo;
    private final AiGatewayClient                 gatewayClient;
    private final AiAuditOpinionRepository        opinionRepo;
    private final ReviewerRiskScoreRepository     riskScoreRepo;
    private final ApplicationEventPublisher       eventPublisher;
    private final ObjectMapper                    objectMapper;
    private final AiGatewayProperties             properties;

    @Transactional
    public void analyzeReports(List<Long> advrIds) {
        if (advrIds == null || advrIds.isEmpty()) return;

        List<ReviewAdvisoryReport> reports = reportRepo.findAllById(advrIds).stream()
                .filter(r -> ReviewAdvisoryReport.SEVERITY_WARN.equals(r.getSeverityCd())
                          || ReviewAdvisoryReport.SEVERITY_CRITICAL.equals(r.getSeverityCd()))
                .toList();

        if (reports.isEmpty()) return;

        Map<Long, List<ReviewAdvisoryReport>> byRevId = reports.stream()
                .collect(Collectors.groupingBy(ReviewAdvisoryReport::getRevId));

        for (Map.Entry<Long, List<ReviewAdvisoryReport>> entry : byRevId.entrySet()) {
            processRevGroup(entry.getKey(), entry.getValue());
        }
    }

    private void processRevGroup(Long revId, List<ReviewAdvisoryReport> reports) {
        Long reviewerId = reports.stream()
                .map(ReviewAdvisoryReport::getTargetReviewerId)
                .filter(id -> id != null)
                .findFirst().orElse(null);

        String maskedOpinion = loadMaskedOpinion(revId);

        List<ReviewAdvisoryReport> biasReports = reports.stream()
                .filter(r -> ADVISORY_TYPE_BIAS.equals(r.getAdvisoryTypeCd())).toList();
        List<ReviewAdvisoryReport> complianceReports = reports.stream()
                .filter(r -> !ADVISORY_TYPE_BIAS.equals(r.getAdvisoryTypeCd())).toList();

        if (!biasReports.isEmpty()) {
            List<GatewaySignalSummary> signals = loadSignals(biasReports);
            List<GatewayRagChunk> ragChunks = extractRagChunks(biasReports);
            callAndSave(revId, reviewerId, biasReports, AiAuditOpinion.TYPE_BIAS, signals, maskedOpinion, ragChunks);
        }

        if (!complianceReports.isEmpty()) {
            List<GatewaySignalSummary> signals = loadSignals(complianceReports);
            List<GatewayRagChunk> ragChunks = extractRagChunks(complianceReports);
            callAndSave(revId, reviewerId, complianceReports, AiAuditOpinion.TYPE_COMPLIANCE, signals, maskedOpinion, ragChunks);
        }
    }

    private void callAndSave(Long revId, Long reviewerId,
                             List<ReviewAdvisoryReport> groupReports,
                             String analysisType,
                             List<GatewaySignalSummary> signals,
                             String maskedOpinion,
                             List<GatewayRagChunk> ragChunks) {
        Long advrId = groupReports.get(0).getAdvrId();
        log.info("RAG 청크 주입 — revId={} type={} chunks={}", revId, analysisType, ragChunks.size());
        try {
            GatewayAnalysisResponse resp = gatewayClient.analyze(new GatewayAnalysisRequest(
                    analysisType, revId, reviewerId, maskedOpinion, signals, ragChunks));

            String citedJson = serializeChunkIds(resp.citedChunkIds());
            opinionRepo.save(AiAuditOpinion.builder()
                    .advrId(advrId)
                    .revId(revId)
                    .reviewerId(reviewerId)
                    .analysisTypeCd(analysisType)
                    .conclusionCd(resp.conclusion())
                    .reasoningSummary(resp.reasoningSummary())
                    .confidenceScore(resp.confidenceScore())
                    .inputTokens(resp.inputTokens())
                    .outputTokens(resp.outputTokens())
                    .citedChunkIds(citedJson)
                    .generatedAt(OffsetDateTime.now())
                    .build());

            if (reviewerId != null) {
                updateRiskScore(reviewerId, analysisType, resp.conclusion());
            }

            if (isSuspected(resp.conclusion())) {
                quarantine(groupReports, revId, reviewerId, resp.conclusion(), analysisType);
            }

            log.info("감사 의견 저장 — revId={} type={} conclusion={} confidence={}",
                    revId, analysisType, resp.conclusion(), resp.confidenceScore());
        } catch (Exception e) {
            log.warn("AuditFairnessAgent 분석 실패 (무시) — revId={} type={}: {}",
                    revId, analysisType, e.getMessage());
        }
    }

    /**
     * advrPayload.citations 에서 GatewayRagChunk 목록을 추출한다.
     * 파싱 실패·citations 없는 리포트는 건너뛰고(best-effort), 동일 chunkId 는 점수 높은 것으로 중복 제거,
     * 점수 내림차순 후 maxChunks 상한 적용, 내용은 maxContentChars 로 절단.
     */
    List<GatewayRagChunk> extractRagChunks(List<ReviewAdvisoryReport> reports) {
        int maxChunks = properties.getRag().getMaxChunks();
        int maxChars  = properties.getRag().getMaxContentChars();

        // chunkId → 최고점 CitationItem
        Map<Long, CitationItem> byChunkId = new LinkedHashMap<>();

        for (ReviewAdvisoryReport report : reports) {
            String payload = report.getAdvrPayload();
            if (payload == null) continue;
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode citationsNode = root.get("citations");
                if (citationsNode == null || !citationsNode.isArray()) continue;

                for (JsonNode node : citationsNode) {
                    CitationItem item = objectMapper.treeToValue(node, CitationItem.class);
                    byChunkId.merge(item.chunkId(), item,
                            (a, b) -> a.score() >= b.score() ? a : b);
                }
            } catch (Exception e) {
                log.warn("advrPayload citations 파싱 실패 (무시) — advrId={}: {}",
                        report.getAdvrId(), e.getMessage());
            }
        }

        return byChunkId.values().stream()
                .sorted(Comparator.comparingDouble(CitationItem::score).reversed())
                .limit(maxChunks)
                .map(item -> new GatewayRagChunk(
                        item.docCd() + " §" + item.sectionPath(),
                        truncate(item.chunkText(), maxChars)))
                .toList();
    }

    private String serializeChunkIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            log.warn("citedChunkIds 직렬화 실패 (무시): {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String text, int maxChars) {
        return text != null && text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static boolean isSuspected(String conclusionCd) {
        return AiAuditOpinion.CONCLUSION_BIAS_SUSPECTED.equals(conclusionCd)
                || AiAuditOpinion.CONCLUSION_VIOLATION_SUSPECTED.equals(conclusionCd);
    }

    private void quarantine(List<ReviewAdvisoryReport> reports, Long revId, Long reviewerId,
                            String conclusionCd, String analysisType) {
        OffsetDateTime now = OffsetDateTime.now();
        reports.forEach(r -> r.markQuarantined(now));
        reportRepo.saveAll(reports);

        List<Long> advrIds = reports.stream().map(ReviewAdvisoryReport::getAdvrId).toList();
        eventPublisher.publishEvent(new QuarantineTriggeredEvent(revId, reviewerId, conclusionCd, analysisType, advrIds));
        log.warn("격리(Quarantine) 신호 발행 — revId={} reviewer={} conclusion={} reports={}",
                revId, reviewerId, conclusionCd, advrIds);
    }

    private void updateRiskScore(Long reviewerId, String analysisType, String conclusionCd) {
        ReviewerRiskScore score = riskScoreRepo.findByReviewerId(reviewerId)
                .orElseGet(() -> ReviewerRiskScore.init(reviewerId));
        if (AiAuditOpinion.TYPE_BIAS.equals(analysisType)) {
            score.applyBiasConclusion(conclusionCd);
        } else {
            score.applyComplianceConclusion(conclusionCd);
        }
        riskScoreRepo.save(score);
    }

    private String loadMaskedOpinion(Long revId) {
        return loanReviewRepo.findById(revId)
                .map(LoanReview::getRevRemark)
                .map(PiiMaskingUtil::mask)
                .orElse(null);
    }

    private List<GatewaySignalSummary> loadSignals(List<ReviewAdvisoryReport> reports) {
        List<GatewaySignalSummary> result = new ArrayList<>();
        for (ReviewAdvisoryReport report : reports) {
            for (ReviewAdvisorySignal s : signalRepo.findByAdvrIdOrderByObservedAtAsc(report.getAdvrId())) {
                result.add(new GatewaySignalSummary(
                        report.getAdvisoryTypeCd(),
                        report.getSeverityCd(),
                        s.getSignalMetric(),
                        s.getObservedValue() != null ? s.getObservedValue().doubleValue() : 0.0,
                        s.getThresholdValue() != null ? s.getThresholdValue().doubleValue() : 0.0
                ));
            }
        }
        return result;
    }

}
