package com.bank.loan.advisory.domain.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * LLM 감사 의견 저장. review-ai-gateway 호출 결과를 append-only 기록.
 * 한 리포트(advrId)에 최대 2건 (BIAS_DETECTION + COMPLIANCE_VERIFICATION).
 */
@Getter
@Entity
@Table(name = "ai_audit_opinion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class AiAuditOpinion {

    public static final String TYPE_BIAS        = "BIAS_DETECTION";
    public static final String TYPE_COMPLIANCE  = "COMPLIANCE_VERIFICATION";

    public static final String CONCLUSION_BIAS_SUSPECTED       = "BIAS_SUSPECTED";
    public static final String CONCLUSION_NO_BIAS              = "NO_BIAS_DETECTED";
    public static final String CONCLUSION_VIOLATION_SUSPECTED  = "VIOLATION_SUSPECTED";
    public static final String CONCLUSION_COMPLIANT            = "COMPLIANT";
    public static final String CONCLUSION_INSUFFICIENT         = "INSUFFICIENT_DATA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "opinion_id")
    private Long opinionId;

    @Column(name = "advr_id")
    private Long advrId;

    @Column(name = "rev_id", nullable = false)
    private Long revId;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "analysis_type_cd", nullable = false, length = 50)
    private String analysisTypeCd;

    @Column(name = "conclusion_cd", nullable = false, length = 50)
    private String conclusionCd;

    @Column(name = "reasoning_summary", length = 2000)
    private String reasoningSummary;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    /** LLM이 tool 호출로 참조한 chunk_id 목록, JSON 배열 직렬화 (예: [123,456]). null 허용. */
    @Column(name = "cited_chunk_ids", columnDefinition = "TEXT")
    private String citedChunkIds;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;
}
