package com.bank.aigateway.audit;

import com.bank.aigateway.agent.AgenticLoop;
import com.bank.aigateway.agent.AgenticLoopResult;
import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.observability.GatewayMetrics;
import com.bank.aigateway.parser.AuditResponseParser;
import com.bank.aigateway.tool.ToolRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgenticAuditAnalysisService {

    static final String TYPE_BIAS       = "BIAS_DETECTION";
    static final String TYPE_COMPLIANCE = "COMPLIANCE_VERIFICATION";

    private static final String BIAS_SYSTEM = """
            당신은 금융기관의 여신 심사 공정성을 검토하는 감사 전문가입니다.
            심사관의 의사결정 패턴과 통계적 편향 신호를 분석하여 차별적 또는 불공정한 심사 행위를 탐지합니다.

            분석에 필요한 경우 아래 도구를 활용하여 추가 정보를 조회하십시오:
            - get_policy_citation: 관련 정책·규정 조항 검색 (편향 판단 기준 등)
            - get_similar_cases: 유사 신청자 과거 심사 사례 조회 (비교 분석용)
            - get_reviewer_history: 심사관 최근 결정 이력 조회 (패턴 분석용)
            - get_cohort_stats: 코호트별 편향 통계 조회 (집단 비교용)

            충분한 근거를 수집한 후 반드시 아래 JSON 형식으로만 최종 응답하십시오. 다른 텍스트를 포함하지 마십시오.
            {
              "conclusion": "BIAS_SUSPECTED | NO_BIAS_DETECTED | INSUFFICIENT_DATA",
              "reasoningSummary": "한국어로 된 200자 이내 감사 의견",
              "confidenceScore": 0.0 ~ 1.0,
              "citedChunkIds": [chunk_id, ...]
            }
            citedChunkIds 는 get_policy_citation 또는 get_similar_cases 결과에서 실제로 결론 도출에 사용한 chunk_id 목록입니다. 미사용 시 빈 배열([])을 반환하십시오.
            """;

    private static final String COMPLIANCE_SYSTEM = """
            당신은 금융기관의 여신 규정 준수 여부를 검증하는 감사 전문가입니다.
            DSR/LTV 한도 초과, 예외 처리 남용, 내부 규정 우회 패턴을 탐지합니다.

            분석에 필요한 경우 아래 도구를 활용하여 추가 정보를 조회하십시오:
            - get_policy_citation: 관련 여신 규정 조항 검색 (규정 우회 여부 확인용)
            - get_similar_cases: 유사 신청자 과거 심사 사례 조회 (예외 처리 빈도 비교용)
            - get_reviewer_history: 심사관 최근 결정 이력 조회 (예외 승인 패턴 분석용)
            - get_cohort_stats: 코호트별 예외 처리율 통계 조회

            충분한 근거를 수집한 후 반드시 아래 JSON 형식으로만 최종 응답하십시오. 다른 텍스트를 포함하지 마십시오.
            {
              "conclusion": "VIOLATION_SUSPECTED | COMPLIANT | INSUFFICIENT_DATA",
              "reasoningSummary": "한국어로 된 200자 이내 감사 의견",
              "confidenceScore": 0.0 ~ 1.0,
              "citedChunkIds": [chunk_id, ...]
            }
            citedChunkIds 는 get_policy_citation 또는 get_similar_cases 결과에서 실제로 결론 도출에 사용한 chunk_id 목록입니다. 미사용 시 빈 배열([])을 반환하십시오.
            """;

    private final AgenticLoop   agenticLoop;
    private final ToolRegistry  toolRegistry;
    private final AuditResponseParser parser;
    private final GatewayMetrics      metrics;

    public AuditAnalysisResponse analyze(AuditAnalysisRequest req) {
        Timer.Sample timer = metrics.startAnalysisTimer();
        try {
            String systemPrompt = resolveSystemPrompt(req.analysisType());
            String userMessage  = buildUserMessage(req);

            AgenticLoopResult loopResult = agenticLoop.run(
                    systemPrompt,
                    userMessage,
                    toolRegistry.allDefinitions(),
                    toolRegistry.asExecutorFunction()
            );

            metrics.recordTokens(req.analysisType(), loopResult.inputTokens(), loopResult.outputTokens());

            if (loopResult.timedOut()) {
                metrics.recordLoopTimeout(req.analysisType());
            }
            metrics.recordLoopTurns(req.analysisType(), loopResult.turnsUsed(), loopResult.timedOut());

            AuditResponseParser.ParsedAuditResult parsed = parser.parse(loopResult.text());
            metrics.recordAnalysisResult(req.analysisType(), parsed.conclusion());
            log.info("agentic 감사 분석 완료 — revId={} type={} conclusion={} confidence={} turns={}",
                    req.revId(), req.analysisType(), parsed.conclusion(), parsed.confidenceScore(), loopResult.turnsUsed());

            return new AuditAnalysisResponse(
                    req.analysisType(),
                    parsed.conclusion(),
                    parsed.reasoningSummary(),
                    parsed.confidenceScore(),
                    loopResult.inputTokens(),
                    loopResult.outputTokens(),
                    parsed.citedChunkIds()
            );
        } finally {
            metrics.recordAnalysisDuration(timer, req.analysisType());
        }
    }

    private String resolveSystemPrompt(String analysisType) {
        return switch (analysisType) {
            case TYPE_BIAS       -> BIAS_SYSTEM;
            case TYPE_COMPLIANCE -> COMPLIANCE_SYSTEM;
            default -> throw new IllegalArgumentException("지원하지 않는 analysisType: " + analysisType);
        };
    }

    private String buildUserMessage(AuditAnalysisRequest req) {
        String signals = req.signals() == null || req.signals().isEmpty() ? "(없음)" :
                req.signals().stream()
                        .map(s -> "- [%s/%s] metric=%s observed=%.4f threshold=%.4f".formatted(
                                s.ruleCd(), s.severityCd(), s.signalMetric(),
                                s.observedValue(), s.thresholdValue()))
                        .collect(Collectors.joining("\n"));

        String opinion = req.reviewOpinionText() == null ? "(없음)" : req.reviewOpinionText();

        return """
                [심사 건 정보]
                심사 ID: %d
                심사관 ID: %d

                [탐지된 신호]
                %s

                [심사관 의견서]
                %s

                위 정보를 바탕으로, 필요한 경우 도구를 활용하여 추가 정보를 조회한 후 분석 결론을 도출하십시오.
                """.formatted(req.revId(), req.reviewerId(), signals, opinion);
    }
}
