package com.bank.ai.agent;

/**
 * 에이전트 추론 요약 — LLM structured output record.
 *
 * <p>pre-review-agent-plan.md §5 추론 요약 — Gemini 2.5 Flash 가 1~2문장 한국어로 생성.
 * fallback 시 {@code PreReviewAgentService} 가 템플릿 문장으로 대체.
 *
 * @param summary 1~2문장 한국어 위험도 요약. 수치 근거 포함, 판단 편향 배제.
 */
public record AgentReasoningSummary(String summary) {}
