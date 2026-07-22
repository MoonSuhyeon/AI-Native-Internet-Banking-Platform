package com.bank.ai.shadow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shadow Mode 설정 — application.yml {@code ai.shadow} 섹션.
 *
 * <p>기본값: disabled. 운영 배포 초기 2주 shadow 검증 시 {@code AI_SHADOW_ENABLED=true} 로 활성.
 *
 * @param enabled                shadow run 전체 활성 여부 (kill switch)
 * @param model                  shadow 전용 모델명
 * @param promptVersion          검증할 프롬프트 버전 태그
 * @param divergeScoreThreshold  decisionScore 차이 임계 (초과 시 diverged=true)
 * @param samplingRate           shadow 적용 샘플링 비율 (0.0~1.0)
 * @param timeoutSeconds         shadow run 타임아웃 (초)
 * @param ragEnabled             shadow run 이 RAG 컨텍스트를 사용하는지 여부 (D4-2)
 * @param citationDiffThreshold  policyFlags 수 차이 임계 — 초과 시 POLICY_FLAG_DIFF 기록 (D4-2)
 * @param ragBackend             shadow run 이 사용한 RAG 백엔드 (inline / es) (E4-2)
 */
@ConfigurationProperties(prefix = "ai.shadow")
public record ShadowRunProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("stub-v1") String model,
        @DefaultValue("v1") String promptVersion,
        @DefaultValue("0.10") double divergeScoreThreshold,
        @DefaultValue("1.0") double samplingRate,
        @DefaultValue("45") int timeoutSeconds,
        @DefaultValue("false") boolean ragEnabled,
        @DefaultValue("2") int citationDiffThreshold,
        @DefaultValue("inline") String ragBackend
) {}
