package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신청 진행 상황(journey) 한눈 조회 통합 테스트.
 *
 * 시나리오:
 *   10) 전체 단계 완료(신용대출, 본심사 APPROVED) — 모든 단계 응답 포함, LTV 빈 list
 *   11) 일부 단계만 (신청 → 가심사만 완료) — prescreening 만 채워짐, 나머지 null
 *   12) 담보 대출 전체 흐름 — LTV list 1건 포함, 본심사 APPROVED
 *   13) 신청 직후 (단계 0) — 모든 단계 null/빈
 *   14) 미존재 applId → 404 LOAN_012
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanApplicationJourneyFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 30_000_000L;
    private static final int  MONTHS  = 36;
    private static final int  BASE_BPS = 500;

    private Long creditProdId;
    private Long mortgageProdId;
    private Long fullApplId;       // 전체 흐름 완료
    private Long prescOnlyApplId;  // 가심사만
    private Long mortgageApplId;   // 담보 대출 전체 흐름
    private Long freshApplId;      // 신청 직후

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        creditProdId = createCreditProduct();
        activateProduct(creditProdId);
        mortgageProdId = createMortgageProduct();
        activateProduct(mortgageProdId);

        // 전체 흐름 — 신용대출 APPROVED
        fullApplId = createApplication(creditProdId, 14001);
        prepFullyEligible(fullApplId, 50_000_000L);
        runReviewApprovedManually(fullApplId);

        // 가심사만
        prescOnlyApplId = createApplication(creditProdId, 14002);
        runPrescreening(prescOnlyApplId);

        // 담보 대출 전체 흐름
        mortgageApplId = createApplication(mortgageProdId, 14003);
        prepFullyEligible(mortgageApplId, 200_000_000L);
        Long col = createCollateral(mortgageApplId);
        evaluateCollateral(col, 200_000_000L);  // LTV PASS
        runLtv(col);
        runReviewApprovedManually(mortgageApplId);

        // 신청만, 단계 없음
        freshApplId = createApplication(creditProdId, 14004);
    }

    @Test @Order(10)
    void 전체단계_완료_응답() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/journey", fullApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.applId").value(fullApplId))
                .andExpect(jsonPath("$.data.prescreening.prescResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.creditEvaluation.cevalDecisionCd").value("APPROVE"))
                .andExpect(jsonPath("$.data.dsr.dsrStatusCd").value("PASS"))
                // 신용대출 — 담보 없음
                .andExpect(jsonPath("$.data.ltv.length()").value(0))
                .andExpect(jsonPath("$.data.review.revDecisionCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.review.revStatusCd").value("COMPLETED"));
    }

    @Test @Order(11)
    void 가심사만_완료_나머지_null() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/journey", prescOnlyApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.applId").value(prescOnlyApplId))
                .andExpect(jsonPath("$.data.prescreening.prescResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.creditEvaluation").doesNotExist())
                .andExpect(jsonPath("$.data.dsr").doesNotExist())
                .andExpect(jsonPath("$.data.ltv.length()").value(0))
                .andExpect(jsonPath("$.data.review").doesNotExist());
    }

    @Test @Order(12)
    void 담보대출_LTV_list_포함() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/journey", mortgageApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.applId").value(mortgageApplId))
                .andExpect(jsonPath("$.data.ltv.length()").value(1))
                .andExpect(jsonPath("$.data.ltv[0].ltvStatusCd").value("PASS"))
                .andExpect(jsonPath("$.data.review.revDecisionCd").value("APPROVED"));
    }

    @Test @Order(13)
    void 신청직후_모든_단계_없음() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/journey", freshApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.applId").value(freshApplId))
                .andExpect(jsonPath("$.data.prescreening").doesNotExist())
                .andExpect(jsonPath("$.data.creditEvaluation").doesNotExist())
                .andExpect(jsonPath("$.data.dsr").doesNotExist())
                .andExpect(jsonPath("$.data.ltv.length()").value(0))
                .andExpect(jsonPath("$.data.review").doesNotExist());
    }

    @Test @Order(14)
    void 미존재_applId_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/journey", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createCreditProduct() throws Exception {
        String code = "JRN_C_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"journey 신용대출", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(code, BASE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private Long createMortgageProduct() throws Exception {
        String code = "JRN_M_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"journey 담보대출", "loanTypeCd":"MORTGAGE",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":1000000000,
                  "minPeriodMo":12, "maxPeriodMo":360,
                  "collateralRequiredYn":true, "guarantorRequiredYn":false
                }
                """.formatted(code, BASE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prodStatusCd":"ACTIVE" }
                                """))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId, long customerId) throws Exception {
        String body = """
                {
                  "customerId":%d, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(customerId, prodId, AMOUNT, MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void runPrescreening(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
    }

    private void runCeval(Long applId, long evalLimit) throws Exception {
        String body = """
                {
                  "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE", "cevalScore":700,
                  "evalLimitAmount":%d
                }
                """.formatted(evalLimit);
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runDsrPass(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":10000000 }
                                """))
                .andExpect(status().isCreated());
    }

    private void prepFullyEligible(Long applId, long cevalLimit) throws Exception {
        runPrescreening(applId);
        runCeval(applId, cevalLimit);
        runDsrPass(applId);
        runIdv(applId);
    }

    private void runIdv(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678" }
                                """))
                .andExpect(status().isCreated());
    }

    private void runReviewApprovedManually(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"MANUAL", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated());
    }

    private Long createCollateral(Long applId) throws Exception {
        String body = """
                {
                  "colTypeCd":"REAL_ESTATE", "colName":"journey 담보",
                  "declaredValue":200000000, "currencyCd":"KRW", "ownershipTypeCd":"SOLE",
                  "seniorLienYn":false, "seniorLienAmount":0
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("colId").asLong();
    }

    private void evaluateCollateral(Long colId, long appliedValue) throws Exception {
        String body = """
                {
                  "evalMethodCd":"APPRAISAL", "evalAgencyCd":"KAB",
                  "appraisedValue":%d, "appliedValue":%d,
                  "appliedStartDate":"20260101", "appliedEndDate":"20271231"
                }
                """.formatted(appliedValue, appliedValue);
        mockMvc.perform(post("/api/collaterals/{colId}/evaluations", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runLtv(Long colId) throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", colId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }
}
