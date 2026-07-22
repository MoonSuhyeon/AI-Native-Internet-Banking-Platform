package com.bank.aigateway.llm.agentic;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(
        String id,
        String name,
        JsonNode input
) {}
