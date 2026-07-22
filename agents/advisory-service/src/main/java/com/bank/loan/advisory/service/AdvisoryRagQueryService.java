package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.support.LoanErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * RAG 관련 조회 서비스 — advr_payload 에 캐시된 citations 파싱 등.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryRagQueryService {

    private final ReviewAdvisoryReportRepository reportRepo;
    private final ObjectMapper                   objectMapper;

    /**
     * 리포트 advr_payload.citations 에 저장된 정책 인용 목록 반환.
     * CRITICAL 룰 발화 시 AdvisoryEvaluator 가 자동 적재한 결과.
     * 인용이 없거나 파싱 실패 시 빈 목록 반환.
     */
    @Transactional(readOnly = true)
    public PolicyCitationResponse getCitations(Long advrId) {
        ReviewAdvisoryReport report = reportRepo.findById(advrId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190));

        List<PolicyCitationResponse.CitationItem> items = parseCitations(report.getAdvrPayload());
        return new PolicyCitationResponse(advrId, items.size(), items);
    }

    private List<PolicyCitationResponse.CitationItem> parseCitations(String advrPayload) {
        if (advrPayload == null || advrPayload.isBlank()) return Collections.emptyList();
        try {
            JsonNode root = objectMapper.readTree(advrPayload);
            JsonNode cit  = root.get("citations");
            if (cit == null || cit.isNull() || !cit.isArray()) return Collections.emptyList();
            return objectMapper.readValue(
                    objectMapper.writeValueAsString(cit),
                    new TypeReference<List<PolicyCitationResponse.CitationItem>>() {});
        } catch (Exception e) {
            log.warn("advr_payload citations 파싱 실패 — advrId=?: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
