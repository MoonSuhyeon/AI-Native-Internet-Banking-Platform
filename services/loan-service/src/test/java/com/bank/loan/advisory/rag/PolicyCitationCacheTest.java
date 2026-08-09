package com.bank.loan.advisory.rag;

import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyCitationCacheTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private PolicyCitationCache cache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RULE_CD    = "RULE_DTI_EXCEED";
    private static final String QUERY_TEXT = "DSR 초과 — 대출 한도 초과";
    private static final int    TOP_K      = 3;

    @BeforeEach
    void setUp() {
        cache = new PolicyCitationCache(redis, objectMapper);
        ReflectionTestUtils.setField(cache, "ttlHours", 6L);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void 캐시_미스_empty_반환() {
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<List<PolicyCitationResponse.CitationItem>> result = cache.get(RULE_CD, QUERY_TEXT, TOP_K);

        assertThat(result).isEmpty();
    }

    @Test
    void 캐시_히트_역직렬화_반환() throws Exception {
        List<PolicyCitationResponse.CitationItem> items = sampleItems();
        String json = objectMapper.writeValueAsString(items);
        when(valueOps.get(anyString())).thenReturn(json);

        Optional<List<PolicyCitationResponse.CitationItem>> result = cache.get(RULE_CD, QUERY_TEXT, TOP_K);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).chunkId()).isEqualTo(10L);
    }

    @Test
    void 저장_호출시_set_with_ttl() throws Exception {
        List<PolicyCitationResponse.CitationItem> items = sampleItems();

        cache.put(RULE_CD, QUERY_TEXT, TOP_K, items);

        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofHours(6)));
    }

    @Test
    void 빈_결과는_저장하지_않음() {
        cache.put(RULE_CD, QUERY_TEXT, TOP_K, List.of());

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 동일_쿼리는_동일_캐시키() {
        when(valueOps.get(anyString())).thenReturn(null);

        cache.get(RULE_CD, QUERY_TEXT, TOP_K);
        cache.get(RULE_CD, QUERY_TEXT, TOP_K);

        verify(valueOps, times(2)).get(argThat((String k) ->
                k.startsWith(PolicyCitationCache.KEY_PREFIX + RULE_CD + ":" + TOP_K + ":")));
    }

    @Test
    void 쿼리_달라지면_캐시키_다름() throws Exception {
        List<PolicyCitationResponse.CitationItem> items = sampleItems();

        cache.put(RULE_CD, QUERY_TEXT, TOP_K, items);
        cache.put(RULE_CD, "다른 쿼리 텍스트", TOP_K, items);

        verify(valueOps, times(2)).set(
                argThat(k -> k.startsWith(PolicyCitationCache.KEY_PREFIX)),
                anyString(),
                any(Duration.class));

        var keys = mockingDetails(valueOps).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("set"))
                .map(i -> (String) i.getArgument(0))
                .toList();
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    void Redis_오류_발생시_empty_반환_예외_미전파() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 연결 실패"));

        Optional<List<PolicyCitationResponse.CitationItem>> result = cache.get(RULE_CD, QUERY_TEXT, TOP_K);

        assertThat(result).isEmpty();
    }

    @Test
    void evictByRuleCd_키_삭제() {
        Set<String> keysToDelete = Set.of(
                "rag:policy:RULE_DTI_EXCEED:3:abc",
                "rag:policy:RULE_DTI_EXCEED:5:def");
        when(redis.keys("rag:policy:" + RULE_CD + ":*")).thenReturn(keysToDelete);

        cache.evictByRuleCd(RULE_CD);

        verify(redis).delete(keysToDelete);
    }

    private List<PolicyCitationResponse.CitationItem> sampleItems() {
        return List.of(new PolicyCitationResponse.CitationItem(
                10L, 1L, "DSR_RULE_01", "DSR 규정", "char:0", "DSR 비율은 40% 이하...", 0.92));
    }
}
