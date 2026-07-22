package com.bank.docagent.submission.dto.extracted;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * doc-agent 표준 출력 JSON의 extracted_data 블록.
 * 각 필드는 서류 유형별로 일부만 채워진다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredData(
    @JsonProperty("applicant")      ApplicantInfo applicant,
    @JsonProperty("financial_info") FinancialInfo financialInfo,
    @JsonProperty("collateral_info")CollateralInfo collateralInfo
) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApplicantInfo(
        @JsonProperty("name")             ExtractedField<String>  name,
        @JsonProperty("masked_ssn")       ExtractedField<String>  maskedSsn,
        @JsonProperty("address")          ExtractedField<String>  address,
        @JsonProperty("employment_status")ExtractedField<String>  employmentStatus,
        @JsonProperty("company")          ExtractedField<String>  company,
        @JsonProperty("hire_date")        ExtractedField<String>  hireDate,
        @JsonProperty("issue_date")       ExtractedField<String>  issueDate,
        @JsonProperty("has_official_seal")ExtractedField<Boolean> hasOfficialSeal
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FinancialInfo(
        @JsonProperty("annual_income")       ExtractedField<Long>    annualIncome,
        @JsonProperty("attribution_year")    ExtractedField<Integer> attributionYear,
        @JsonProperty("income_source_verified") boolean              incomeSourceVerified
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollateralInfo(
        @JsonProperty("property_address") ExtractedField<String>  propertyAddress,
        @JsonProperty("owner_name")       ExtractedField<String>  ownerName,
        @JsonProperty("prior_bond_amount")ExtractedField<Long>    priorBondAmount,
        @JsonProperty("is_clean_title")   ExtractedField<Boolean> isCleanTitle,
        @JsonProperty("sale_price")       ExtractedField<Long>    salePrice,
        @JsonProperty("contract_date")    ExtractedField<String>  contractDate
    ) {}
}
