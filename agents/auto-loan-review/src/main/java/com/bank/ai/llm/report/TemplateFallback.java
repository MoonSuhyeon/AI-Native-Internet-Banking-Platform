package com.bank.ai.llm.report;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 미가용·실패·grounding 실패 시 결정론적 ReviewReport 생성.
 *
 * <p>plan/llm-pipeline.md §0 ·§5.4 — 호출 측은 항상 유효한 ReviewReport 를 받음.
 * 톤은 단조롭지만 citation 은 인라인 정책에서 정확히 가져와 grounding 통과 보장.
 *
 * <p>운영 의도: 토큰 cap 초과 / provider 장애 / 환각 인용 검출 시 자동 우회. 메트릭은
 * {@code llm.calls.status=fallback} 로 별도 카운트 (후속 phase).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateFallback {

    private final InlinePolicyIndex policyIndex;

    public ReviewReport generate(ReviewReportInput input, String reason) {
        log.info("ReviewReport template fallback track={} reason={}", input.track(), reason);
        return switch (input.track()) {
            case TRACK_1 -> approveTemplate(input, reason);
            case TRACK_2 -> rejectTemplate(input, reason);
            case TRACK_3 -> humanReviewTemplate(input, reason);
        };
    }

    private ReviewReport approveTemplate(ReviewReportInput in, String reason) {
        var summary = ("자동 승인 권고. PD %.4f 가 안전여유 임계 %.4f 이하라 "
                + "정책 매트릭스 기준 강하게 안전 구간. 신청자 페르소나 (%s) 와 상품 (%s) "
                + "조합도 정책 정상 범위.")
                .formatted(in.pdScore(), in.safetyMarginThreshold(),
                        in.personaSummary(), in.productCode());
        return new ReviewReport(
                Track.TRACK_1,
                summary,
                List.of(),
                List.of(strengthFromCitation("LOW_PD",
                        "PD %.4f, 안전여유 임계 %.4f 이하".formatted(in.pdScore(), in.safetyMarginThreshold()),
                        "PD_THRESHOLD_MATRIX_V1")),
                "심사원 sign-off 후 승인 처리 권고.",
                citations("PD_THRESHOLD_MATRIX_V1", "AUTO_REVIEW_GOVERNANCE_V1"),
                reason
        );
    }

    private ReviewReport rejectTemplate(ReviewReportInput in, String reason) {
        List<ReviewReport.RiskFactor> risks = new ArrayList<>();
        for (var hf : in.hardFails()) {
            String citationId = citationFor(hf);
            risks.add(new ReviewReport.RiskFactor(
                    hf.code(),
                    hf.message(),
                    1.0,
                    citationId
            ));
        }
        if (risks.isEmpty()) {
            // PD 초과로 인한 Track 2 — hard fail 없음
            risks.add(new ReviewReport.RiskFactor(
                    "PD_EXCEEDED",
                    "PD %.4f > 매트릭스 임계 %.4f".formatted(in.pdScore(), in.pdThreshold()),
                    1.0,
                    "PD_THRESHOLD_MATRIX_V1"
            ));
        }
        var firstPara = ("자동 반려 권고. " + risks.stream().map(ReviewReport.RiskFactor::description)
                .reduce((a, b) -> a + "; " + b).orElse("") + ".");
        var secondPara = "통보 시 거절 사유로 위 항목을 명시하시기 바랍니다. "
                + "자세한 사유 안내는 RejectionNoticeService 산출 통보문을 참조.";
        return new ReviewReport(
                Track.TRACK_2,
                firstPara + "\n\n" + secondPara,
                risks,
                List.of(),
                "심사원 sign-off 후 거절 통보 처리 권고.",
                // Track 2 는 ≥ 2 인용 강제 (GroundingValidator)
                distinctCitations(risks, "AUTO_REVIEW_GOVERNANCE_V1"),
                reason
        );
    }

    private ReviewReport humanReviewTemplate(ReviewReportInput in, String reason) {
        var p1 = ("[위험요인] PD %.4f 가 정책 매트릭스 임계 %.4f 이하지만 안전여유 %.4f 는 초과 — "
                + "회색지대. 단순 자동 분기 부적합.")
                .formatted(in.pdScore(), in.pdThreshold(), in.safetyMarginThreshold());
        var p2 = "[강점] hard constraint 위반 없음. 결정 신뢰도와 PD 간 상충 신호 부재.";
        var p3 = "[권고] 심사원 심층 판단 — 거래내역·신청 사유 정합성·유사 케이스 검토 후 결정.";
        return new ReviewReport(
                Track.TRACK_3,
                p1 + "\n\n" + p2 + "\n\n" + p3,
                List.of(new ReviewReport.RiskFactor(
                        "PD_GRAY_ZONE",
                        "PD 회색지대 (안전여유 초과, 매트릭스 이하)",
                        0.5,
                        "PD_THRESHOLD_MATRIX_V1"
                )),
                List.of(new ReviewReport.Strength(
                        "RULE_PASS",
                        "정책 hard constraint 통과",
                        "AUTO_REVIEW_GOVERNANCE_V1"
                )),
                "심사원 심층 판단 필수.",
                citations("PD_THRESHOLD_MATRIX_V1", "AUTO_REVIEW_GOVERNANCE_V1"),
                reason
        );
    }

    /** hard fail 코드 → 인라인 정책 citation id 매핑. */
    private String citationFor(HardFailReason hf) {
        return switch (hf) {
            case DSR_EXCEEDED -> "MORT_DSR_LIMIT_V1";
            case LTV_EXCEEDED -> "MORT_LTV_LIMIT_V1";
            case CREDIT_SCORE_BELOW_MIN -> "CRED_SCORE_MIN_V1";
            case DELINQUENCY_24M_PRESENT -> "DELINQ_24M_BAR_V1";
            case AGE_BELOW_MIN -> "AGE_MIN_V1";
        };
    }

    private ReviewReport.Strength strengthFromCitation(String code, String desc, String citationId) {
        return new ReviewReport.Strength(code, desc, citationId);
    }

    private List<ReviewReport.Citation> citations(String... ids) {
        List<ReviewReport.Citation> out = new ArrayList<>();
        for (String id : ids) {
            policyIndex.get(id).ifPresent(entry ->
                    out.add(new ReviewReport.Citation(id, entry.source(), entry.text())));
        }
        return out;
    }

    private List<ReviewReport.Citation> distinctCitations(
            List<ReviewReport.RiskFactor> risks, String extra
    ) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var r : risks) {
            if (r.citationId() != null) ids.add(r.citationId());
        }
        ids.add(extra);
        return citations(ids.toArray(String[]::new));
    }
}
