package com.bank.aigateway.tool;

import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.executor.CohortStatsToolExecutor;
import com.bank.aigateway.tool.executor.PolicyCitationToolExecutor;
import com.bank.aigateway.tool.executor.ReviewerHistoryToolExecutor;
import com.bank.aigateway.tool.executor.SimilarCasesToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

class ToolRegistryTest {

    ObjectMapper objectMapper = new ObjectMapper();
    ToolExecutor policyExec   = mock(ToolExecutor.class);
    ToolExecutor similarExec  = mock(ToolExecutor.class);
    ToolExecutor historyExec  = mock(ToolExecutor.class);
    ToolExecutor cohortExec   = mock(ToolExecutor.class);

    ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        stubExecutor(policyExec,  PolicyCitationToolExecutor.TOOL_NAME);
        stubExecutor(similarExec, SimilarCasesToolExecutor.TOOL_NAME);
        stubExecutor(historyExec, ReviewerHistoryToolExecutor.TOOL_NAME);
        stubExecutor(cohortExec,  CohortStatsToolExecutor.TOOL_NAME);
        registry = new ToolRegistry(List.of(policyExec, similarExec, historyExec, cohortExec));
    }

    @Test
    void allDefinitions_4종_반환() {
        List<ToolDefinition> defs = registry.allDefinitions();
        assertThat(defs).hasSize(4);
        assertThat(defs.stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder(
                        PolicyCitationToolExecutor.TOOL_NAME,
                        SimilarCasesToolExecutor.TOOL_NAME,
                        ReviewerHistoryToolExecutor.TOOL_NAME,
                        CohortStatsToolExecutor.TOOL_NAME
                );
    }

    @Test
    void asExecutorFunction_올바른_executor로_라우팅() {
        when(policyExec.execute(any())).thenReturn("정책 결과");
        Function<ToolCall, String> fn = registry.asExecutorFunction();

        String result = fn.apply(new ToolCall("id-1", PolicyCitationToolExecutor.TOOL_NAME,
                objectMapper.createObjectNode()));

        assertThat(result).isEqualTo("정책 결과");
        verify(policyExec).execute(any());
        verify(similarExec, never()).execute(any());
        verify(historyExec, never()).execute(any());
        verify(cohortExec,  never()).execute(any());
    }

    @Test
    void 미등록_tool_호출_시_빈_문자열_반환() {
        Function<ToolCall, String> fn = registry.asExecutorFunction();
        String result = fn.apply(new ToolCall("id-x", "unknown_tool", objectMapper.createObjectNode()));
        assertThat(result).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubExecutor(ToolExecutor exec, String name) throws Exception {
        when(exec.toolName()).thenReturn(name);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        when(exec.definition()).thenReturn(new ToolDefinition(name, "설명", schema));
    }
}
