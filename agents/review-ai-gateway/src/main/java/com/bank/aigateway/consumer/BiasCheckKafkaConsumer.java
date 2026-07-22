package com.bank.aigateway.consumer;

import com.bank.aigateway.audit.AgenticAuditAnalysisService;
import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.audit.dto.SignalSummary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * loan.bias-check-requested 토픽 소비자.
 * LoanBiasCheckPayload → AuditAnalysisRequest 변환 후 AgenticAuditAnalysisService 실행,
 * 결과를 loan-service 콜백으로 전달.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiasCheckKafkaConsumer {

    private static final String TOPIC    = "loan.bias-check-requested";
    private static final String GROUP_ID = "review-ai-gateway";

    private final AgenticAuditAnalysisService auditService;
    private final LoanServiceCallbackClient callbackClient;

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID)
    public void consume(LoanBiasCheckPayload payload) {
        Long revId = payload.revId();
        log.info("bias-check 수신 revId={} applId={}", revId, payload.applId());
        try {
            AuditAnalysisRequest req = toAuditRequest(payload);
            AuditAnalysisResponse result = auditService.analyze(req);
            callbackClient.reportResult(revId, result);
        } catch (Exception e) {
            log.error("bias-check 분석 실패 revId={}", revId, e);
            callbackClient.reportFailure(revId, e.getMessage());
        }
    }

    private AuditAnalysisRequest toAuditRequest(LoanBiasCheckPayload p) {
        LoanBiasCheckPayload.ReviewerDecision dec = p.reviewerDecision();
        LoanBiasCheckPayload.ReviewContext ctx     = p.context();

        List<SignalSummary> signals = new ArrayList<>();
        signals.add(new SignalSummary("DECISION", "INFO", "decisionCd", 0.0, 0.0));

        if (ctx != null) {
            if (ctx.cbScore() != null) {
                signals.add(new SignalSummary("CB_SCORE", "INFO", "cevalScore",
                        ctx.cbScore().doubleValue(), 0.0));
            }
            if (ctx.dsrRatioBps() != null) {
                double dsrRatio  = ctx.dsrRatioBps() / 10000.0;
                double dsrLimit  = ctx.dsrLimitBps() != null ? ctx.dsrLimitBps() / 10000.0 : 0.0;
                signals.add(new SignalSummary("DSR_RATIO", "INFO", "dsrRatio", dsrRatio, dsrLimit));
            }
            if (ctx.ltvRatioBps() != null) {
                signals.add(new SignalSummary("LTV_RATIO", "INFO", "ltvRatio",
                        ctx.ltvRatioBps() / 10000.0, 0.0));
            }
        }

        Long reviewerId = dec != null && dec.reviewerId() != null ? dec.reviewerId() : 0L;

        return new AuditAnalysisRequest(
                "BIAS_DETECTION",
                p.revId(),
                reviewerId,
                null,
                signals,
                List.of()
        );
    }
}
