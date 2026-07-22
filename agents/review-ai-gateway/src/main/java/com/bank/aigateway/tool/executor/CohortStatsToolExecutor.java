package com.bank.aigateway.tool.executor;

import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.AdvisoryHttpClient;
import com.bank.aigateway.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class CohortStatsToolExecutor implements ToolExecutor {

    public static final String TOOL_NAME = "get_cohort_stats";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "dimension": {
                  "type": "string",
                  "description": "코호트 차원 코드 (예: EMPLOYMENT_TYPE, LOAN_PURPOSE)"
                },
                "value": {
                  "type": "string",
                  "description": "차원 값 (예: SELF_EMPLOYED, HOME_PURCHASE)"
                }
              },
              "required": ["dimension", "value"]
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
                "특정 코호트(고용형태·대출목적 등)의 승인율·거절율 통계를 조회합니다. 집단 간 편향 수치를 비교할 때 활용하십시오.",
                objectMapper.readTree(SCHEMA));
    }

    @Override
    public String execute(JsonNode input) {
        String dimension = input.path("dimension").asText("");
        String value     = input.path("value").asText("");
        if (dimension.isBlank() || value.isBlank()) return "";
        String encodedDim = URLEncoder.encode(dimension, StandardCharsets.UTF_8);
        String encodedVal = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return httpClient.get("/api/internal/advisory/cohort-stats?dimension=" + encodedDim + "&value=" + encodedVal)
                .orElse("");
    }
}
