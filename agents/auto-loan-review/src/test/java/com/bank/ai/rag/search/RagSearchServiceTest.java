package com.bank.ai.rag.search;

import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagSearchServiceTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec statementSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<Boolean> boolQuerySpec;
    @Mock
    private AgentMetricsRecorder metricsRecorder;

    private RagSearchService service;

    @BeforeEach
    void setUp() {
        var props = new RagSearchProperties(0.7, 0.5, 5);
        service = new RagSearchService(jdbcClient, new StubEmbeddingClient(), props, metricsRecorder);
    }

    @Test
    void existsById_존재하는_id는_true() {
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statementSpec);
        when(statementSpec.query(Boolean.class)).thenReturn(boolQuerySpec);
        when(boolQuerySpec.single()).thenReturn(Boolean.TRUE);

        assertThat(service.existsById(1L)).isTrue();
    }

    @Test
    void existsById_존재하지_않는_id는_false() {
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statementSpec);
        when(statementSpec.query(Boolean.class)).thenReturn(boolQuerySpec);
        when(boolQuerySpec.single()).thenReturn(Boolean.FALSE);

        assertThat(service.existsById(99999L)).isFalse();
    }

    @Test
    void search_예외_발생_시_빈_리스트_반환() {
        when(jdbcClient.sql(anyString())).thenThrow(new RuntimeException("DB 오류"));

        var result = service.search("policy_regulation", "DSR 한도", null, 3);
        assertThat(result).isEmpty();
    }
}
