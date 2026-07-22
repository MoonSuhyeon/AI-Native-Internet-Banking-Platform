package com.bank.aigateway.tool;

import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

public interface ToolExecutor {
    String toolName();
    ToolDefinition definition();
    String execute(JsonNode input);
}
