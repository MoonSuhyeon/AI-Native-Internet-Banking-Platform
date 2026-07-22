package com.bank.ai.llm.notice;

import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.report.ReviewReport;
import com.bank.ai.rule.domain.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Track 2 자동반려 시 고객향 거절 통보문 생성 — plan/llm-pipeline.md §2 (D).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejectionNoticeService {

    private final LlmClient llmClient;
    private final LlmProperties props;

    public RejectionNotice generate(ReviewReport report) {
        if (report.track() != Track.TRACK_2) {
            return null;
        }

        if (!props.enabled()) {
            return fallback();
        }

        try {
            String systemPrompt = "당신은 은행 고객 서비스 담당자입니다. 대출 거절 사유를 친절하고 법적으로 명확하게 안내하는 통보문을 작성하세요.";
            String userContent = String.format("심사 요약: %s\n위험 요인: %s", 
                    report.summary(), report.riskFactors());

            LlmRequest request = new LlmRequest(
                    "rejection_notice",
                    1,
                    systemPrompt,
                    userContent,
                    props.maxTokens(),
                    props.temperature()
            );

            return llmClient.call(request, RejectionNotice.class);
        } catch (Exception e) {
            log.error("Failed to generate rejection notice via LLM, falling back", e);
            return fallback();
        }
    }

    private RejectionNotice fallback() {
        return new RejectionNotice(
                "[안내] 대출 신청 심사 결과",
                "신청하신 대출이 자행 심사 기준에 미달하여 승인이 거절되었습니다. 상세 사유는 심사 리포트를 확인해 주세요.",
                List.of("신용정보법 §32")
        );
    }
}
