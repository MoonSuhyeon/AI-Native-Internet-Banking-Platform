package com.bank.ai.rag.retrieval;

import com.bank.ai.agent.guard.AgentLoopGuard;
import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rag.search.RagSearchBackend;
import com.bank.ai.rag.search.RagSearchProperties;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.shadow.canary.CanaryRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 코드 오케스트레이션 진입점 — phase-d-rag.md D2-3.
 *
 * <p>Java 코드가 검색 순서를 결정 (LLM 자율 tool calling X). 트랙별 callCapsPerTrack 상한과
 * {@link AgentLoopGuard} 양쪽이 소진되면 현재까지 수집된 청크를 반환 (partial result).
 *
 * <p>{@code ai.rag.enabled=false} 시 즉시 빈 리스트 반환 — kill switch.
 *
 * <p>{@code ai.canary.enabled=true} 시 {@link CanaryRouter} 가 optional 주입되어 per-request 분기.
 * canary 가 "inline" 을 선택하면 RAG 없이 빈 리스트 반환 — inline fallback 경로로 처리.
 *
 * <p>D2: 정책 코퍼스(policy_regulation) 1회 검색. D3 에서 similar_cases 검색 추가.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    static final String POLICY_CORPUS = "policy_regulation";
    static final String SIMILAR_CASES_CORPUS = "similar_cases";

    private final RagSearchBackend ragSearchBackend;
    private final RagSearchProperties searchProps;
    private final RagProperties ragProps;

    /** Canary 활성 시에만 주입 — null 이면 static 설정(ragProps.enabled) 으로만 판단. */
    @Nullable
    @Autowired(required = false)
    private CanaryRouter canaryRouter;

    /**
     * AgentLoopGuard 통합 다중 코퍼스 검색 — D2(정책) + D3(유사 케이스).
     *
     * <p>guard.acquireTool() 이 false 를 반환하거나 callCapsPerTrack 을 초과하면 즉시 중단.
     * 각 RAG 검색마다 tool 슬롯 1개 소비 — 기존 도구 호출과 동일한 예산 풀.
     *
     * @param track          현재 트랙 (cap 계산 기준)
     * @param policyQuery    정책 코퍼스 자연어 질의
     * @param casesQuery     유사 케이스 코퍼스 자연어 질의 (null 이면 D3 스킵)
     * @param casesLoanType  유사 케이스 loan_type 메타 필터 값 (null 이면 필터 없음)
     * @param guard          AgentLoopGuard — null 이면 cap 만으로 제한 (비에이전트 경로)
     * @return 검색된 Chunk 목록 (점수 내림차순). 비활성·cap 초과 시 빈 리스트.
     */
    public List<Chunk> retrieve(Track track, String policyQuery,
                                String casesQuery, String casesLoanType,
                                AgentLoopGuard guard) {
        if (!ragProps.enabled()) {
            return List.of();
        }

        // Canary 라우팅: canaryRouter 가 있으면 per-request 분기
        if (canaryRouter != null && !canaryRouter.shouldUseEs()) {
            log.debug("RagRetrievalService: [Canary] inline 경로 — RAG skip track={}", track);
            return List.of();
        }

        int cap = ragProps.capForTrack(track.name());
        List<Chunk> result = new ArrayList<>();

        // D2: 정책 코퍼스 1회 검색 (cap >= 1 인 모든 트랙)
        if (cap >= 1) {
            if (guard != null && !guard.acquireTool()) {
                log.warn("RagRetrievalService: LOOP_GUARD_HIT before policy search track={}", track);
                return result;
            }
            var chunks = ragSearchBackend.search(
                    POLICY_CORPUS, policyQuery, null, searchProps.defaultK());
            result.addAll(chunks);
            log.debug("RagRetrievalService: policy search track={} hits={}", track, chunks.size());
        }

        // D3: 유사 케이스 코퍼스 검색 (cap >= 2)
        if (cap >= 2 && casesQuery != null && !casesQuery.isBlank()) {
            if (guard != null && !guard.acquireTool()) {
                log.warn("RagRetrievalService: LOOP_GUARD_HIT before cases search track={}", track);
                return result;
            }
            Map<String, Object> metaFilter = casesLoanType != null
                    ? Map.of("loan_type", casesLoanType) : null;
            var caseChunks = ragSearchBackend.search(
                    SIMILAR_CASES_CORPUS, casesQuery, metaFilter, searchProps.defaultK());
            result.addAll(caseChunks);
            log.debug("RagRetrievalService: similar_cases search track={} hits={}", track, caseChunks.size());
        }

        return result;
    }
}
