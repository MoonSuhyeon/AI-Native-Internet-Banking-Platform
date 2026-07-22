package com.bank.aigateway.agent;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 에이전트 루프 설정 — application.yml {@code aigateway.agent} 섹션과 바인딩.
 *
 * @param maxTurns 단일 agentic loop run에서 허용하는 최대 턴 수.
 *                 초과 시 INSUFFICIENT_DATA JSON 폴백 반환.
 *                 근거: 정상종료 run turns p95 + run당 토큰예산 역산 중 min.
 *                 (agent-loop-limits-metrics-plan.md 참조)
 */
@Validated
@ConfigurationProperties(prefix = "aigateway.agent")
public record AgenticLoopProperties(
        @DefaultValue("5") @Min(1) int maxTurns
) {
}
