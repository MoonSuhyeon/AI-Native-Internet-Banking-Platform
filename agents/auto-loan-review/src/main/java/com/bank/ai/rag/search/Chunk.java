package com.bank.ai.rag.search;

import java.util.Map;

/**
 * RAG 검색 결과 단위 — rag-corpora.md §5.3.
 *
 * @param id          ai_embedding.id
 * @param corpus      코퍼스 식별자 ('policy_regulation' / 'similar_cases' / 'internal_faq')
 * @param sourceId    원본 키 (정책 doc id, LOAN_REVIEW rev_id, FAQ id 등)
 * @param text        chunk_text (검색 결과 표시용 원문)
 * @param summary     chunk_summary (LLM 입력용 짧은 요약, null 가능)
 * @param metadata    JSONB 메타 (matrix_coord, tags, decision_label 등)
 * @param hybridScore 벡터 가중 + FTS 가중합 점수 [0, 1]
 */
public record Chunk(
        long id,
        String corpus,
        String sourceId,
        String text,
        String summary,
        Map<String, Object> metadata,
        double hybridScore
) {
    /** LLM prompt 에 주입할 요약 텍스트 — summary 있으면 summary, 없으면 text 앞 500자. */
    public String promptText() {
        if (summary != null && !summary.isBlank()) return summary;
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
