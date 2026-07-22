package com.bank.aigateway.llm.agentic;

public record ToolResult(
        String toolUseId,
        String content
) {}
