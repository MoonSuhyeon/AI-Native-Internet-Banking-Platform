package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import com.bank.loan.advisory.dto.AiAuditOpinionResponse;
import com.bank.loan.advisory.dto.QuarantineReportResponse;
import com.bank.loan.advisory.dto.ReviewerRiskScoreResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.audit.AiAuditOpinionRepository;
import com.bank.loan.advisory.repository.audit.ReviewerRiskScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditOpinionQueryService {

    private static final int MAX_TOP = 100;

    private final AiAuditOpinionRepository        opinionRepo;
    private final ReviewerRiskScoreRepository     riskScoreRepo;
    private final ReviewAdvisoryReportRepository  reportRepo;

    @Transactional(readOnly = true)
    public List<AiAuditOpinionResponse> opinionsByAdvr(Long advrId) {
        return opinionRepo.findByAdvrId(advrId).stream()
                .map(AiAuditOpinionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiAuditOpinionResponse> opinionsByReviewer(Long reviewerId) {
        return opinionRepo.findByReviewerIdOrderByGeneratedAtDesc(reviewerId).stream()
                .map(AiAuditOpinionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewerRiskScoreResponse riskScore(Long reviewerId) {
        ReviewerRiskScore score = riskScoreRepo.findByReviewerId(reviewerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_404,
                        "reviewerId=" + reviewerId + " 위험도 스코어 없음"));
        return ReviewerRiskScoreResponse.from(score);
    }

    @Transactional(readOnly = true)
    public List<ReviewerRiskScoreResponse> topByBias(int limit) {
        return riskScoreRepo.findAllByOrderByBiasScoreDesc(PageRequest.of(0, clamp(limit))).stream()
                .map(ReviewerRiskScoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewerRiskScoreResponse> topByCompliance(int limit) {
        return riskScoreRepo.findAllByOrderByComplianceScoreDesc(PageRequest.of(0, clamp(limit))).stream()
                .map(ReviewerRiskScoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiAuditOpinionResponse> recentOpinions(int limit) {
        return opinionRepo.findAllByOrderByGeneratedAtDesc(PageRequest.of(0, clamp(limit))).stream()
                .map(AiAuditOpinionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QuarantineReportResponse> quarantinedReports() {
        return reportRepo.findAllQuarantined().stream()
                .map(QuarantineReportResponse::of)
                .toList();
    }

    private int clamp(int limit) {
        return Math.min(Math.max(1, limit), MAX_TOP);
    }
}
