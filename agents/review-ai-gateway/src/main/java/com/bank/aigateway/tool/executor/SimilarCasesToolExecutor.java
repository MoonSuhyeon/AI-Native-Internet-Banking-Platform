package com.bank.aigateway.tool.executor;

import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.AdvisoryHttpClient;
import com.bank.aigateway.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimilarCasesToolExecutor implements ToolExecutor {

    public static final String TOOL_NAME = "get_similar_cases";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "rev_id": {
                  "type": "integer",
                  "description": "유사 사례를 조회할 기준 심사 ID"
                }
              },
              "required": ["rev_id"]
            }
            """;

    private final AdvisoryHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() { return TOOL_NAME; }

    @Override
    @SneakyThrows
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME,
                "신용점수·DSR·LTV가 유사한 과거 신청자들의 심사 결과를 조회합니다. 동일 조건 신청자 간 결과 차이를 분석할 때 활용하십시오.",
                objectMapper.readTree(SCHEMA));
    }

    @Override
    public String execute(JsonNode input) {
        long revId = input.path("rev_id").asLong(0);
        if (revId == 0) return "";
        return httpClient.get("/api/internal/advisory/similar-cases?revId=" + revId)
                .orElse("");
    }
}
