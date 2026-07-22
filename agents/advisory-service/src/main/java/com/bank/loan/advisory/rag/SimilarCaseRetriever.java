package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.AdvisoryRetrievalLog;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.dto.SimilarCaseResponse;
import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.repository.AdvisoryRetrievalLogRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import io.micrometer.core.instrument.Timer;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 유사 과거 심사 사례 코사인 유사도 검색 (Path C — advisory_case_index 자체 검색).
 *
 * advisory_case_index 테이블을 직접 쿼리한다 (ai-service 위임 보류).
 * 자기 자신(revId 일치) 제외 후 결과 반환.
 *
 * <p>AI_GUIDELINES: 트랜잭션 안에서 외부 API 호출 금지 → retrieve 에 메서드 레벨 트랜잭션을
 * 두지 않는다. embed/조회는 트랜잭션 밖에서 수행되고, 감사 로그 save 는 Spring Data 가
 * 각자 트랜잭션을 열어 best-effort 로 적재한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarCaseRetriever {

    private static final int DEFAULT_TOP_K = 5;

    private static final String COSINE_SQL = """
            SELECT ci.case_idx_id, ci.rev_id, ci.decision_cd, ci.overturn_yn,
                   ci.credit_score, ci.dsr_ratio_bps, ci.ltv_ratio_bps,
                   ci.cohort_employment_type_cd, ci.cohort_loan_purpose_cd,
                   ci.summary_text,
                   1 - (ci.embedding <=> CAST(? AS vector)) AS score
            FROM advisory_case_index ci
            WHERE ci.rev_id <> ?
            ORDER BY ci.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final EmbeddingClient                embeddingClient;
    private final JdbcTemplate                   jdbc;
    private final ReviewAdvisoryReportRepository reportRepo;
    private final LoanReviewRepository           reviewRepo;
    private final AdvisoryRetrievalLogRepository logRepo;
    private final AdvisoryMetrics                advisoryMetrics;

    public SimilarCaseResponse retrieve(Long advrId, Long actorId) {
        return retrieve(advrId, DEFAULT_TOP_K, actorId);
    }

    public SimilarCaseResponse retrieve(Long advrId, int topK, Long actorId) {
        Timer.Sample sample = advisoryMetrics.startRagSearchTimer();
        boolean success = false;
        try {
            ReviewAdvisoryReport report = reportRepo.findById(advrId)
                    .filter(r -> r.getDeletedAt() == null)
                    .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190));

            LoanReview review = reviewRepo.findById(report.getRevId())
                    .filter(r -> r.getDeletedAt() == null)
                    .orElse(null);

            String queryText = buildQueryText(report, review);
            float[] qVec = embeddingClient.embed(queryText);
            String vecStr = EmbeddingClient.toVectorString(qVec);

            List<SimilarCaseResponse.CaseItem> items = jdbc.query(
                    COSINE_SQL,
                    (rs, rn) -> new SimilarCaseResponse.CaseItem(
                            rs.getLong("case_idx_id"),
                            rs.getLong("rev_id"),
                            rs.getString("decision_cd"),
                            rs.getString("overturn_yn"),
                            rs.getObject("credit_score", Integer.class),
                            rs.getObject("dsr_ratio_bps", Integer.class),
                            rs.getObject("ltv_ratio_bps", Integer.class),
                            rs.getString("cohort_employment_type_cd"),
                            rs.getString("cohort_loan_purpose_cd"),
                            rs.getString("summary_text"),
                            rs.getDouble("score")),
                    vecStr, report.getRevId(), vecStr, topK);

            appendLog(advrId, queryText, embeddingClient.defaultModelCd(), items.size(),
                    items.isEmpty() ? null : items.get(0).score(), actorId);
            advisoryMetrics.recordRagSearchResults(items.size(), AdvisoryRetrievalLog.KIND_SIMILAR_CASE);
            success = true;
            return new SimilarCaseResponse(advrId, items.size(), items);
        } finally {
            advisoryMetrics.recordRagSearchDuration(sample,
                    AdvisoryRetrievalLog.KIND_SIMILAR_CASE, success ? "success" : "error");
        }
    }

    // ──────────────────────────────────────────────────

    private String buildQueryText(ReviewAdvisoryReport report, LoanReview review) {
        if (review == null) {
            return String.format("룰코드: %s 심각도: %s", report.getRuleId(), report.getSeverityCd());
        }
        return String.format("심사결정: %s 심사유형: %s 심사관ID: %s 룰코드: %s",
                review.getRevDecisionCd(), review.getRevTypeCd(),
                review.getReviewerId(), report.getRuleId());
    }

    private void appendLog(Long advrId, String queryText, String modelCd,
                           int resultCount, Double topScore, Long actorId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            logRepo.save(AdvisoryRetrievalLog.builder()
                    .advrId(advrId)
                    .retrievalKindCd(AdvisoryRetrievalLog.KIND_SIMILAR_CASE)
                    .queryText(queryText)
                    .queryEmbeddingModelCd(modelCd)
                    .resultCount(resultCount)
                    .topScore(topScore != null
                            ? BigDecimal.valueOf(topScore).setScale(6, RoundingMode.HALF_UP)
                            : null)
                    .requestedBy(actorId)
                    .requestedAt(now)
                    .createdAt(now)
                    .createdBy(actorId != null ? actorId : 0L)
                    .build());
        } catch (Exception e) {
            log.warn("검색 감사 로그 적재 실패 (무시) — advrId={}: {}", advrId, e.getMessage());
        }
    }
}
