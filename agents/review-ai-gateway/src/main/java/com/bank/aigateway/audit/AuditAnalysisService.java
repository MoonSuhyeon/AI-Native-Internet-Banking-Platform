package com.bank.aigateway.audit;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.llm.LlmClient;
import com.bank.aigateway.llm.LlmRequest;
import com.bank.aigateway.llm.LlmResponse;
import com.bank.aigateway.observability.GatewayMetrics;
import com.bank.aigateway.parser.AuditResponseParser;
import com.bank.aigateway.prompt.bias.BiasDetectionPromptBuilder;
import com.bank.aigateway.prompt.compliance.ComplianceVerificationPromptBuilder;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAnalysisService {

    static final String TYPE_BIAS        = "BIAS_DETECTION";
    static final String TYPE_COMPLIANCE  = "COMPLIANCE_VERIFICATION";

    private final LlmClient llmClient;
    private final BiasDetectionPromptBuilder       biasPromptBuilder;
    private final ComplianceVerificationPromptBuilder compliancePromptBuilder;
    private final AuditResponseParser parser;
    private final GatewayMetrics metrics;

    public AuditAnalysisResponse analyze(AuditAnalysisRequest req) {
        Timer.Sample timer = metrics.startAnalysisTimer();
        try {
            LlmRequest llmReq = buildLlmRequest(req);
            LlmResponse llmResp = llmClient.complete(llmReq);
            metrics.recordTokens(req.analysisType(), llmResp.inputTokens(), llmResp.outputTokens());

            AuditResponseParser.ParsedAuditResult parsed = parser.parse(llmResp.content());
            log.info("감사 분석 완료 — revId={} type={} conclusion={} confidence={}",
                    req.revId(), req.analysisType(), parsed.conclusion(), parsed.confidenceScore());

            return new AuditAnalysisResponse(
                    req.analysisType(),
                    parsed.conclusion(),
                    parsed.reasoningSummary(),
                    parsed.confidenceScore(),
                    llmResp.inputTokens(),
                    llmResp.outputTokens(),
                    List.of()
            );
        } finally {
            metrics.recordAnalysisDuration(timer, req.analysisType());
        }
    }

    private LlmRequest buildLlmRequest(AuditAnalysisRequest req) {
        return switch (req.analysisType()) {
            case TYPE_BIAS -> LlmRequest.of(
                    biasPromptBuilder.buildSystem(),
                    biasPromptBuilder.buildUser(req));
            case TYPE_COMPLIANCE -> LlmRequest.of(
                    compliancePromptBuilder.buildSystem(),
                    compliancePromptBuilder.buildUser(req));
            default -> throw new IllegalArgumentException("지원하지 않는 analysisType: " + req.analysisType());
        };
    }
}
