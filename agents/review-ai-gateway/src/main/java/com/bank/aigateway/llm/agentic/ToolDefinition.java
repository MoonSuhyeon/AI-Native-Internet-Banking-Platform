package com.bank.aigateway.llm.agentic;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {}
