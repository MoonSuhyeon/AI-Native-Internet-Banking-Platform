package com.bank.ai.rag.retrieval;

import com.bank.ai.agent.guard.AgentLoopGuard;
import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rag.search.RagSearchProperties;
import com.bank.ai.rag.search.RagSearchService;
import com.bank.ai.rule.domain.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock
    private RagSearchService ragSearchService;

    private static final RagSearchProperties SEARCH_PROPS = new RagSearchProperties(0.7, 0.5, 5);

    private Chunk stubChunk(long id, String corpus) {
        return new Chunk(id, corpus, "src-" + id, "텍스트", null, Map.of(), 0.9);
    }

    @Test
    void rag_비활성시_검색_호출_없이_빈_리스트() {
        var props = new RagProperties(false, "inline", Map.of("TRACK_3", 5));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(6, 2);

        var result = service.retrieve(Track.TRACK_3, "주담대 DSR 한도", null, null, guard);

        assertThat(result).isEmpty();
        verify(ragSearchService, never()).search(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void guard_cap_소진시_검색_전_중단_빈_리스트() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_3", 5));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(0, 2);

        var result = service.retrieve(Track.TRACK_3, "주담대 DSR", null, null, guard);

        assertThat(result).isEmpty();
        verify(ragSearchService, never()).search(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void 정상_정책_검색_청크_반환() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_3", 5));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(6, 2);
        when(ragSearchService.search(eq("policy_regulation"), anyString(), isNull(), anyInt()))
                .thenReturn(List.of(stubChunk(1L, "policy_regulation"), stubChunk(2L, "policy_regulation")));

        var result = service.retrieve(Track.TRACK_3, "주담대 정책 한도", null, null, guard);

        assertThat(result).hasSize(2);
        assertThat(guard.getToolCallCount()).isEqualTo(1);
    }

    @Test
    void guard_null이면_cap만으로_제한() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_1", 1));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        when(ragSearchService.search(anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(List.of(stubChunk(10L, "policy_regulation")));

        var result = service.retrieve(Track.TRACK_1, "정책 질의", null, null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void cap_0인_트랙은_검색_스킵() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_1", 0));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(6, 2);

        var result = service.retrieve(Track.TRACK_1, "정책", null, null, guard);

        assertThat(result).isEmpty();
        verify(ragSearchService, never()).search(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void d3_유사케이스_검색_cap2이상일때_추가반환() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_2", 2));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(6, 2);

        when(ragSearchService.search(eq("policy_regulation"), anyString(), isNull(), anyInt()))
                .thenReturn(List.of(stubChunk(1L, "policy_regulation")));
        when(ragSearchService.search(eq("similar_cases"), anyString(), any(), anyInt()))
                .thenReturn(List.of(stubChunk(2L, "similar_cases"), stubChunk(3L, "similar_cases")));

        var result = service.retrieve(
                Track.TRACK_2, "주담대 정책 한도",
                "MORT_001 regular DSR 35% 유사 케이스", "MORT_001", guard);

        assertThat(result).hasSize(3);
        assertThat(guard.getToolCallCount()).isEqualTo(2);
        verify(ragSearchService).search(eq("similar_cases"), anyString(),
                eq(Map.of("loan_type", "MORT_001")), anyInt());
    }

    @Test
    void d3_casesQuery_null이면_유사케이스_스킵() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_2", 2));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(6, 2);

        when(ragSearchService.search(eq("policy_regulation"), anyString(), isNull(), anyInt()))
                .thenReturn(List.of(stubChunk(1L, "policy_regulation")));

        var result = service.retrieve(Track.TRACK_2, "주담대 정책 한도", null, null, guard);

        assertThat(result).hasSize(1);
        verify(ragSearchService, never()).search(eq("similar_cases"), anyString(), any(), anyInt());
    }

    @Test
    void d3_guard_소진시_정책검색만_반환() {
        var props = new RagProperties(true, "inline", Map.of("TRACK_2", 2));
        var service = new RagRetrievalService(ragSearchService, SEARCH_PROPS, props);
        var guard = new AgentLoopGuard(1, 2);  // 슬롯 1개만 남음

        when(ragSearchService.search(eq("policy_regulation"), anyString(), isNull(), anyInt()))
                .thenReturn(List.of(stubChunk(1L, "policy_regulation")));

        var result = service.retrieve(
                Track.TRACK_2, "주담대 정책 한도",
                "MORT_001 DSR 35% 유사 케이스", "MORT_001", guard);

        assertThat(result).hasSize(1);
        verify(ragSearchService, never()).search(eq("similar_cases"), anyString(), any(), anyInt());
    }
}
