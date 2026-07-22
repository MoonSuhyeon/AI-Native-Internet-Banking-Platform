package com.bank.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * RAG 최상위 설정 — application.yml {@code ai.rag} 섹션.
 *
 * <p>E2-3 {@code RagRetrievalService} 에서 kill switch·callCapsPerTrack·backend 를 참조.
 *
 * @param enabled           RAG kill switch. false 시 인라인 policy fallback.
 * @param backend           검색 백엔드. "inline" (기본) / "es" (Elasticsearch Phase E).
 * @param callCapsPerTrack  트랙별 RAG 검색 최대 횟수 — AgentLoopGuard 통합 기준.
 *                          키: "TRACK_1" / "TRACK_2" / "TRACK_3".
 */
@ConfigurationProperties(prefix = "ai.rag")
public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("inline") String backend,
        @DefaultValue Map<String, Integer> callCapsPerTrack
) {
    public RagProperties {
        callCapsPerTrack = callCapsPerTrack != null ? Map.copyOf(callCapsPerTrack) : Map.of();
    }

    /** 트랙별 cap 반환. 설정 없으면 1 (안전 기본값). */
    public int capForTrack(String trackName) {
        return callCapsPerTrack.getOrDefault(trackName, 1);
    }
}
