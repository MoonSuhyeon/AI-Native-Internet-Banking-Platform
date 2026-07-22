package com.bank.ai.rag.seed;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.config.RuleEngineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정책 코퍼스 P1 청크 생성기 — 백엔드 중립.
 *
 * <p>{@code ai.policy.inline} 정책 + {@code ai.rule-engine.pd-threshold-matrix} 셀을
 * {@link PolicyChunk} 목록으로 변환한다. pgvector({@link PolicyCorpusSeedLoader}) 와
 * ES({@code EsPolicyCorpusSeedLoader}) 양쪽이 동일 청크를 공유하도록 적재 sink 와 분리.
 *
 * <p>{@code matrix_coord} 는 ES {@code kb_policy} 매핑의 {@code keyword} 타입과 호환되도록
 * {@code "product/segment"} 문자열로 표현한다.
 */
@Component
@RequiredArgsConstructor
public class PolicyCorpusChunkProvider {

    private final InlinePolicyIndex policyIndex;
    private final RuleEngineProperties ruleEngineProps;

    /** 인라인 정책 + 매트릭스 셀 전체 청크. */
    public List<PolicyChunk> buildChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        chunks.addAll(inlinePolicyChunks());
        chunks.addAll(matrixCellChunks());
        return chunks;
    }

    private List<PolicyChunk> inlinePolicyChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (var entry : policyIndex.inline().entrySet()) {
            String id = entry.getKey();
            PolicyIndex.PolicyEntry policy = entry.getValue();

            String chunkText = "[정책] %s — %s\n\n%s".formatted(policy.source(), id, policy.text());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", policy.source());
            metadata.put("article_no", id);
            metadata.put("tags", List.of("policy", "inline"));

            chunks.add(new PolicyChunk(id, 0, chunkText, policy.text(), metadata));
        }
        return chunks;
    }

    private List<PolicyChunk> matrixCellChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (var productEntry : ruleEngineProps.pdThresholdMatrix().entrySet()) {
            String product = productEntry.getKey();
            for (var segmentEntry : productEntry.getValue().entrySet()) {
                String segment = segmentEntry.getKey();
                double threshold = segmentEntry.getValue();

                String sourceId = "MATRIX_%s_%s".formatted(product, segment.toUpperCase());
                String chunkText = """
                        [정책 매트릭스] %s / %s
                        PD 임계치: %.3f
                        상품·세그먼트별 PD 임계치 상한 기준. 신용정책위원회 분기 의결 값.
                        적용: product=%s, segment=%s
                        """.formatted(product, segment, threshold, product, segment).trim();
                String summary = "%s %s PD 임계치 %.3f".formatted(product, segment, threshold);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", "policy_matrix");
                metadata.put("product", product);
                metadata.put("segment", segment);
                metadata.put("matrix_coord", "%s/%s".formatted(product, segment));
                metadata.put("tags", List.of("matrix", "pd_threshold"));

                chunks.add(new PolicyChunk(sourceId, 0, chunkText, summary, metadata));
            }
        }
        return chunks;
    }

    /**
     * 백엔드 중립 정책 청크.
     *
     * @param sourceId  원본 키
     * @param chunkSeq  청크 순번
     * @param chunkText 본문 (검색·임베딩 대상)
     * @param summary   요약 (LLM 입력용)
     * @param metadata  메타데이터 (matrix_coord 는 "product/segment" 문자열)
     */
    public record PolicyChunk(
            String sourceId,
            int chunkSeq,
            String chunkText,
            String summary,
            Map<String, Object> metadata
    ) {}
}
