package com.bank.loan.advisory.rag;

import com.bank.loan.advisory.domain.AdvisoryRetrievalLog;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.repository.AdvisoryRetrievalLogRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 정책문서 청크 코사인 유사도 검색 (Path C — advisory_document_chunk 자체 검색).
 *
 * advisory_document_chunk 테이블을 직접 쿼리한다 (ai-service 위임 보류).
 * CRITICAL 룰 발화 시 AdvisoryEvaluator 가 자동 호출 (6-7 훅).
 * 심사관 요청 시 AdvisoryRagController 가 직접 호출.
 * 결과는 ADVISORY_RETRIEVAL_LOG 에 append-only 기록 (감사용).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyCitationRetriever {

    private static final int DEFAULT_TOP_K = 3;

    private static final String COSINE_SQL = """
            SELECT c.chunk_id, c.doc_id, d.doc_cd, d.doc_title, d.source_uri,
                   c.section_path, c.chunk_text, c.embedding_model_cd,
                   1 - (c.embedding <=> CAST(? AS vector)) AS score
            FROM advisory_document_chunk c
            JOIN advisory_document d ON d.doc_id = c.doc_id
            WHERE d.active_yn = 'Y'
              AND d.deleted_at IS NULL
            ORDER BY c.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final EmbeddingClient                embeddingClient;
    private final JdbcTemplate                   jdbc;
    private final PolicyCitationCache            citationCache;
    private final AdvisoryRetrievalLogRepository logRepo;
    private final AdvisoryMetrics                advisoryMetrics;

    /**
     * 정책 인용 검색.
     *
     * @param advrId      검색을 유발한 리포트 ID (감사 로그용; null 허용)
     * @param ruleCd      트리거 룰 코드 (감사 로그용)
     * @param queryText   검색 쿼리 텍스트 (룰 신호 내용 요약)
     * @param topK        반환할 최대 청크 수
     * @param requestedBy 요청자 ID
     * @return 유사도 내림차순 정렬된 인용 목록
     *
     * <p>AI_GUIDELINES: 트랜잭션 안에서 외부 API 호출 금지 → 메서드 레벨 트랜잭션을 두지 않는다.
     * embed/조회는 트랜잭션 밖에서 수행되고, 감사 로그 save 는 {@link #appendLog} 호출 시
     * Spring Data 가 각자 트랜잭션을 열어 best-effort 로 적재한다.
     */
    public PolicyCitationResponse retrieve(Long advrId, String ruleCd,
                                           String queryText, int topK, Long requestedBy) {
        Timer.Sample sample = advisoryMetrics.startRagSearchTimer();
        boolean success = false;
        try {
            List<PolicyCitationResponse.CitationItem> items = citationCache.get(ruleCd, queryText, topK)
                    .orElseGet(() -> {
                        float[] qVec = embeddingClient.embed(queryText);
                        String vecStr = EmbeddingClient.toVectorString(qVec);
                        List<PolicyCitationResponse.CitationItem> fresh = new ArrayList<>(jdbc.query(
                                COSINE_SQL,
                                (rs, rn) -> new PolicyCitationResponse.CitationItem(
                                        rs.getLong("chunk_id"),
                                        rs.getLong("doc_id"),
                                        rs.getString("doc_cd"),
                                        rs.getString("doc_title"),
                                        rs.getString("section_path"),
                                        rs.getString("chunk_text"),
                                        rs.getDouble("score")),
                                vecStr, vecStr, topK));
                        citationCache.put(ruleCd, queryText, topK, fresh);
                        return fresh;
                    });

            appendLog(advrId, ruleCd, queryText, embeddingClient.defaultModelCd(), items.size(),
                    items.isEmpty() ? null : items.get(0).score(), requestedBy);
            advisoryMetrics.recordRagSearchResults(items.size(), AdvisoryRetrievalLog.KIND_POLICY_CITATION);
            success = true;
            return new PolicyCitationResponse(advrId, items.size(), items);
        } finally {
            advisoryMetrics.recordRagSearchDuration(sample,
                    AdvisoryRetrievalLog.KIND_POLICY_CITATION, success ? "success" : "error");
        }
    }

    public PolicyCitationResponse retrieve(Long advrId, String ruleCd, String queryText, Long requestedBy) {
        return retrieve(advrId, ruleCd, queryText, DEFAULT_TOP_K, requestedBy);
    }

    // ──────────────────────────────────────────────────

    private void appendLog(Long advrId, String ruleCd, String queryText,
                           String modelCd, int resultCount, Double topScore, Long requestedBy) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            logRepo.save(AdvisoryRetrievalLog.builder()
                    .advrId(advrId)
                    .retrievalKindCd(AdvisoryRetrievalLog.KIND_POLICY_CITATION)
                    .ruleCd(ruleCd)
                    .queryText(queryText)
                    .queryEmbeddingModelCd(modelCd)
                    .resultCount(resultCount)
                    .topScore(topScore != null
                            ? BigDecimal.valueOf(topScore).setScale(6, RoundingMode.HALF_UP)
                            : null)
                    .requestedBy(requestedBy)
                    .requestedAt(now)
                    .createdAt(now)
                    .createdBy(requestedBy != null ? requestedBy : 0L)
                    .build());
        } catch (Exception e) {
            log.warn("검색 감사 로그 적재 실패 (무시) — advrId={}: {}", advrId, e.getMessage());
        }
    }
}
