package com.bank.ai.rag.seed;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import com.bank.ai.rule.config.RuleEngineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyCorpusSeedLoaderTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec statementSpec;

    /** 동기 실행 executor — runAsync 가 호출 스레드에서 즉시 수행되어 검증 결정론 보장. */
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    private static final InlinePolicyIndex POLICY_INDEX = new InlinePolicyIndex(Map.of(
            "MORT_DSR_LIMIT_V1", new PolicyIndex.PolicyEntry("DSR 40% 이하", "internal_policy"),
            "CRED_SCORE_MIN_V1", new PolicyIndex.PolicyEntry("최저 신용점수 600", "internal_policy")
    ));

    private static final RuleEngineProperties RULE_ENGINE_PROPS = new RuleEngineProperties(
            new RuleEngineProperties.HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30, 0.50,
            Map.of("MORT_001", Map.of("regular", 0.347, "young", 0.750, "senior", 0.788)),
            0.95, 0.20, true, false
    );

    private static final PolicyCorpusChunkProvider CHUNK_PROVIDER =
            new PolicyCorpusChunkProvider(POLICY_INDEX, RULE_ENGINE_PROPS);

    private PolicyCorpusSeedLoader loader(boolean ragEnabled) {
        var ragProps = new RagProperties(ragEnabled, "inline", Map.of("TRACK_3", 5));
        return new PolicyCorpusSeedLoader(
                CHUNK_PROVIDER, jdbcClient, new StubEmbeddingClient(), ragProps, SYNC_EXECUTOR);
    }

    @BeforeEach
    void setUp() {
        lenient().when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        lenient().when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        lenient().when(statementSpec.update()).thenReturn(1);
    }

    @Test
    void enabled_시_inline_2개_matrix_3개_총_5회_upsert() {
        loader(true).run(null);
        // POLICY_INDEX 2개 + MORT_001 matrix 3개(regular, young, senior) = 5회
        verify(jdbcClient, times(5)).sql(anyString());
    }

    @Test
    void rag_비활성_시_seed_스킵() {
        loader(false).run(null);
        verify(jdbcClient, never()).sql(anyString());
    }

    @Test
    void seed_예외는_삼켜져_run이_전파하지_않는다() {
        when(statementSpec.update()).thenThrow(new RuntimeException("DB 오류"));
        // 비동기 + exceptionally 로 삼킴 — run() 은 예외를 던지지 않아야 함 (기동 차단 방지)
        assertThatCode(() -> loader(true).run(null)).doesNotThrowAnyException();
    }
}
