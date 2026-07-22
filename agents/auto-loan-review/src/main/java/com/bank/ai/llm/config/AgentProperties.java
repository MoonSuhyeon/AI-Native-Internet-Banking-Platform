package com.bank.ai.llm.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 사전 심사 에이전트 설정 — application.yml {@code ai.agent} 섹션과 바인딩.
 *
 * <p>pre-review-agent-plan.md 운영 대비책 섹션 참조.
 *
 * @param enabled        에이전트 kill switch. false 시 즉시 AGENT_DISABLED fallback.
 * @param maxToolCalls   단일 agent run 에서 허용하는 도구 호출 최대 횟수 (loop guard).
 * @param maxLlmCalls    단일 agent run 에서 허용하는 LLM 호출 최대 횟수 (loop guard).
 * @param fallbackAfterRpm RPM 초과 시 즉시 fallback (true) 또는 대기 (false, 개발 환경용).
 * @param rateResetHour  RPD 카운터 리셋 UTC 시각 (0 = 자정).
 */
@Validated
@ConfigurationProperties(prefix = "ai.agent")
public record AgentProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("6") @Min(1) int maxToolCalls,
        @DefaultValue("2") @Min(1) int maxLlmCalls,
        @DefaultValue("true") boolean fallbackAfterRpm,
        @DefaultValue("0") int rateResetHour
) {
}
