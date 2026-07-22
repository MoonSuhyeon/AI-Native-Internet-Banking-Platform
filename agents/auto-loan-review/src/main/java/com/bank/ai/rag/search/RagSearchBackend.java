package com.bank.ai.rag.search;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RAG 검색 백엔드 추상 — Phase E (E2-2).
 *
 * <p>{@code ai.rag.backend} 값에 따라 단일 구현이 선택된다:
 * <ul>
 *   <li>{@code inline} (기본) → {@link RagSearchService} (pgvector cosine + FTS, Phase D 레거시)</li>
 *   <li>{@code es} → {@link com.bank.ai.rag.es.search.EsHybridSearchService} (BM25 + kNN RRF)</li>
 * </ul>
 *
 * <p>{@code RagPolicyIndex}·{@code RagRetrievalService} 는 본 인터페이스에만 의존하므로
 * 백엔드 교체 시 두 클래스의 코드 변경이 없다.
 */
public interface RagSearchBackend {

    /**
     * 하이브리드 검색.
     *
     * @param corpus     코퍼스 식별자
     * @param query      자연어 질의
     * @param metaFilter 메타데이터 동등 필터 (null/빈 맵 = 필터 없음)
     * @param k          반환 건수 (0 이하 시 기본값)
     * @return 점수 내림차순 Chunk 목록. 실패 시 빈 리스트.
     */
    List<Chunk> search(String corpus, String query, Map<String, Object> metaFilter, int k);

    /** sourceId 존재 여부 — {@code rag:} citation 검증용. */
    boolean existsBySourceId(String corpus, String sourceId);

    /** sourceId 단건 조회. */
    Optional<Chunk> findBySourceId(String corpus, String sourceId);
}
