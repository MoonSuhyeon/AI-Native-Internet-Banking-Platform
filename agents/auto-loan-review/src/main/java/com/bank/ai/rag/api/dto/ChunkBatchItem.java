package com.bank.ai.rag.api.dto;

import java.util.Map;

/**
 * 배치 적재 단위 청크 — D3-1.
 *
 * @param corpus    코퍼스 식별자 (예: "similar_cases", "policy_regulation")
 * @param sourceId  원본 키 (예: LOAN_REVIEW rev_id 문자열)
 * @param chunkSeq  동일 sourceId 내 청크 순번 (0-based)
 * @param chunkText 적재할 청크 원문
 * @param summary   LLM 입력용 요약 (null 허용 — 없으면 chunkText 앞 500자)
 * @param metadata  JSONB 메타 (corpus별 자유 스키마)
 */
public record ChunkBatchItem(
        String corpus,
        String sourceId,
        int chunkSeq,
        String chunkText,
        String summary,
        Map<String, Object> metadata
) {
    public ChunkBatchItem {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
