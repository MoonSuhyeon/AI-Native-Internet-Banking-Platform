package com.bank.docagent.submission.service;

import com.bank.docagent.infra.llm.LlmExtractClient;
import com.bank.docagent.infra.llm.dto.ExtractResponse;
import com.bank.docagent.submission.dto.extracted.ExtractedField;
import com.bank.docagent.submission.dto.extracted.StructuredData;
import com.bank.docagent.submission.dto.extracted.StructuredData.ApplicantInfo;
import com.bank.docagent.submission.dto.extracted.StructuredData.CollateralInfo;
import com.bank.docagent.submission.dto.extracted.StructuredData.FinancialInfo;
import com.bank.docagent.submission.service.DocumentClassifyService.DocType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * L4 Extract: LLM 호출 결과 → StructuredData 매핑.
 * 서류 유형별로 적절한 블록만 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredExtractService {

    private final LlmExtractClient llmClient;

    public StructuredData extract(String submissionId, DocType docType, String maskedText) {
        ExtractResponse resp = llmClient.extract(submissionId, docType.name(), maskedText);
        Map<String, Object> f = resp.fields() != null ? resp.fields() : Map.of();

        log.info("L4 Extract: submissionId={} docType={} fields={}", submissionId, docType, f.keySet());

        return switch (docType) {
            case ID_CARD            -> mapIdCard(f, docType.name());
            case RESIDENT_REGISTER  -> mapResidentRegister(f, docType.name());
            case EMPLOYMENT_CERT    -> mapEmploymentCert(f, docType.name());
            case INCOME_TAX_RECEIPT -> mapIncomeTax(f, docType.name());
            case REGISTRY_DEED      -> mapRegistryDeed(f, docType.name());
            case SALE_CONTRACT      -> mapSaleContract(f, docType.name());
            default                 -> StructuredData.builder().build();
        };
    }

    // ── 서류별 매핑 ────────────────────────────────────────────────────────

    private StructuredData mapIdCard(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .applicant(ApplicantInfo.builder()
                .name(field(f, "name", src))
                .maskedSsn(field(f, "masked_ssn", src))
                .build())
            .build();
    }

    private StructuredData mapResidentRegister(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .applicant(ApplicantInfo.builder()
                .name(field(f, "name", src))
                .address(field(f, "address", src))
                .issueDate(field(f, "issue_date", src))
                .build())
            .build();
    }

    private StructuredData mapEmploymentCert(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .applicant(ApplicantInfo.builder()
                .name(field(f, "name", src))
                .company(field(f, "company", src))
                .employmentStatus(ExtractedField.of("근로소득자", src))
                .hireDate(field(f, "hire_date", src))
                .issueDate(field(f, "issue_date", src))
                .hasOfficialSeal(boolField(f, "has_official_seal", src))
                .build())
            .build();
    }

    private StructuredData mapIncomeTax(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .applicant(ApplicantInfo.builder()
                .name(field(f, "name", src))
                .employmentStatus(ExtractedField.of("근로소득자", src))
                .build())
            .financialInfo(FinancialInfo.builder()
                .annualIncome(longField(f, "annual_income", src))
                .attributionYear(intField(f, "attribution_year", src))
                .incomeSourceVerified(f.containsKey("annual_income"))
                .build())
            .build();
    }

    private StructuredData mapRegistryDeed(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .collateralInfo(CollateralInfo.builder()
                .propertyAddress(field(f, "property_address", src))
                .ownerName(field(f, "owner_name", src))
                .priorBondAmount(longField(f, "prior_bond_amount", src))
                .isCleanTitle(boolField(f, "is_clean_title", src))
                .build())
            .build();
    }

    private StructuredData mapSaleContract(Map<String, Object> f, String src) {
        return StructuredData.builder()
            .collateralInfo(CollateralInfo.builder()
                .propertyAddress(field(f, "property_address", src))
                .salePrice(longField(f, "sale_price", src))
                .contractDate(field(f, "contract_date", src))
                .build())
            .build();
    }

    // ── 타입 안전 필드 추출 헬퍼 ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> ExtractedField<T> field(Map<String, Object> f, String key, String src) {
        Object v = f.get(key);
        return v != null ? ExtractedField.of((T) v, src) : null;
    }

    private ExtractedField<Long> longField(Map<String, Object> f, String key, String src) {
        Object v = f.get(key);
        if (v == null) return null;
        long val = v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
        return ExtractedField.of(val, src);
    }

    private ExtractedField<Integer> intField(Map<String, Object> f, String key, String src) {
        Object v = f.get(key);
        if (v == null) return null;
        int val = v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
        return ExtractedField.of(val, src);
    }

    private ExtractedField<Boolean> boolField(Map<String, Object> f, String key, String src) {
        Object v = f.get(key);
        if (v == null) return null;
        boolean val = v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
        return ExtractedField.of(val, src);
    }
}
