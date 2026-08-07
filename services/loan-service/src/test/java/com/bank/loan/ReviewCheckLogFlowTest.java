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
 * 본심사 체크 로그 통합 테스트.
 *
 * 시나리오:
 *   10) 신용대출(CREDIT) 본심사 APPROVED → 체크로그 5건 자동 적재
 *       (PRESCREEN_PASS / CB_DECISION / DSR_CHECK / LTV_CHECK=N_A / FINAL_DECISION=PASS)
 *   11) 담보대출(MORTGAGE) 본심사 APPROVED → 체크로그 5건 자동 적재
 *       (LTV_CHECK=PASS)
 *   12) 본심사 REJECTED → FINAL_DECISION=FAIL
 *   13) GET 미존재 revId → 404 LOAN_042
 *   20) POST 수동 추가(DOCUMENT_CHECK PASS) → 201 + 목록 6건
 *   21) POST IDENTITY/CROSS_TRANSACTION/ETC 연속 추가 → 목록 9건
 *   22) POST 자동 적재 코드(PRESCREEN_PASS) → 400 (DTO @Pattern)
 *   23) POST 알 수 없는 result 코드 → 400 (DTO @Pattern)
 *   24) POST 미존재 revId → 404 LOAN_042
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewCheckLogFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 30_000_000L;
    private static final int  MONTHS  = 36;
    private static final int  BASE_BPS = 500;

    private Long creditProdId;
    private Long creditApplId;
    private Long creditRevId;

    private Long mortgageProdId;
    private Long mortgageApplId;
    private Long mortgageRevId;

    private Long rejectedApplId;
    private Long rejectedRevId;

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        // ----- 신용대출: APPROVED -----
        creditProdId = createCreditProduct();
        activateProduct(creditProdId);
        creditApplId = createApplication(creditProdId, /*customerId*/ 11001);
        prepFullyEligible(creditApplId, 50_000_000L);
        creditRevId = runReviewApprovedAuto(creditApplId);

        // ----- 담보대출: APPROVED -----
        mortgageProdId = createMortgageProduct();
        activateProduct(mortgageProdId);
        mortgageApplId = createApplication(mortgageProdId, /*customerId*/ 11002);
        prepFullyEligible(mortgageApplId, 200_000_000L);
        Long col = createCollateral(mortgageApplId);
        evaluateCollateral(col, 200_000_000L);
        runLtv(col);
        mortgageRevId = runReviewApprovedAuto(mortgageApplId);

        // ----- 신용대출: REJECTED -----
        rejectedApplId = createApplication(creditProdId, /*customerId*/ 11003);
        prepFullyEligible(rejectedApplId, 50_000_000L);
        rejectedRevId = runReviewRejected(rejectedApplId);
    }

    @Test @Order(10)
    void 신용대출_APPROVED_체크로그_5건() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", creditRevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].checkItemCd").value("PRESCREEN_PASS"))
                .andExpect(jsonPath("$.data[0].checkResultCd").value("PASS"))
                .andExpect(jsonPath("$.data[1].checkItemCd").value("CB_DECISION"))
                .andExpect(jsonPath("$.data[1].checkResultCd").value("PASS"))
                .andExpect(jsonPath("$.data[2].checkItemCd").value("DSR_CHECK"))
                .andExpect(jsonPath("$.data[2].checkResultCd").value("PASS"))
                .andExpect(jsonPath("$.data[3].checkItemCd").value("LTV_CHECK"))
                .andExpect(jsonPath("$.data[3].checkResultCd").value("N_A"))
                .andExpect(jsonPath("$.data[4].checkItemCd").value("FINAL_DECISION"))
                .andExpect(jsonPath("$.data[4].checkResultCd").value("PASS"));
    }

    @Test @Order(11)
    void 담보대출_APPROVED_LTV_PASS() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", mortgageRevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[3].checkItemCd").value("LTV_CHECK"))
                .andExpect(jsonPath("$.data[3].checkResultCd").value("PASS"))
                .andExpect(jsonPath("$.data[4].checkResultCd").value("PASS"));
    }

    @Test @Order(12)
    void REJECTED_FINAL_DECISION_FAIL() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", rejectedRevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[4].checkItemCd").value("FINAL_DECISION"))
                .andExpect(jsonPath("$.data[4].checkResultCd").value("FAIL"));
    }

    @Test @Order(13)
    void GET_미존재_revId_404() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_042"));
    }

    @Test @Order(20)
    void POST_수동_DOCUMENT_CHECK_추가_201() throws Exception {
        mockMvc.perform(post("/api/loan-reviews/{revId}/checks", creditRevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checkItemCd":"DOCUMENT_CHECK",
                                  "checkResultCd":"PASS",
                                  "checkRemark":"재직증명서·소득증빙 원본 확인",
                                  "checkerId":77001
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revId").value(creditRevId))
                .andExpect(jsonPath("$.data.checkItemCd").value("DOCUMENT_CHECK"))
                .andExpect(jsonPath("$.data.checkResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.checkerId").value(77001));

        // 자동 5건 + 수동 1건 = 6건
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", creditRevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[5].checkItemCd").value("DOCUMENT_CHECK"));
    }

    @Test @Order(21)
    void POST_IDENTITY_CROSS_ETC_연속추가_9건() throws Exception {
        addManual(creditRevId, "IDENTITY_CHECK",    "PASS",   "신분증·얼굴인증 일치");
        addManual(creditRevId, "CROSS_TRANSACTION", "REVIEW", "급여이체 미등록 — 우대 보류");
        addManual(creditRevId, "ETC",               "PASS",   "특이사항 없음");

        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", creditRevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data[6].checkItemCd").value("IDENTITY_CHECK"))
                .andExpect(jsonPath("$.data[7].checkItemCd").value("CROSS_TRANSACTION"))
                .andExpect(jsonPath("$.data[7].checkResultCd").value("REVIEW"))
                .andExpect(jsonPath("$.data[8].checkItemCd").value("ETC"));
    }

    @Test @Order(22)
    void POST_자동적재_코드_거부_400() throws Exception {
        mockMvc.perform(post("/api/loan-reviews/{revId}/checks", creditRevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checkItemCd":"PRESCREEN_PASS",
                                  "checkResultCd":"PASS"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(23)
    void POST_알수없는_result_400() throws Exception {
        mockMvc.perform(post("/api/loan-reviews/{revId}/checks", creditRevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checkItemCd":"ETC",
                                  "checkResultCd":"UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(24)
    void POST_미존재_revId_404() throws Exception {
        mockMvc.perform(post("/api/loan-reviews/{revId}/checks", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checkItemCd":"ETC",
                                  "checkResultCd":"PASS"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_042"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private void addManual(Long revId, String itemCd, String resultCd, String remark) throws Exception {
        String body = """
                {
                  "checkItemCd":"%s",
                  "checkResultCd":"%s",
                  "checkRemark":"%s",
                  "checkerId":77001
                }
                """.formatted(itemCd, resultCd, remark);
        mockMvc.perform(post("/api/loan-reviews/{revId}/checks", revId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createCreditProduct() throws Exception {
        String code = "RVK_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"체크로그 신용대출", "loanTypeCd":"CREDIT",
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
        String code = "RVM_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"체크로그 담보대출", "loanTypeCd":"MORTGAGE",
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

    private void prepFullyEligible(Long applId, long cevalLimit) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
        String cevalBody = """
                {
                  "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE", "cevalScore":700,
                  "evalLimitAmount":%d
                }
                """.formatted(cevalLimit);
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(cevalBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":10000000 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678" }
                                """))
                .andExpect(status().isCreated());
    }

    private Long createCollateral(Long applId) throws Exception {
        String body = """
                {
                  "colTypeCd":"REAL_ESTATE", "colName":"체크로그 테스트 부동산",
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

    private Long runReviewApprovedAuto(Long applId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("revId").asLong();
    }

    private Long runReviewRejected(Long applId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revTypeCd":"MANUAL",
                                  "revDecisionCd":"REJECTED",
                                  "rejectReasonCd":"POLICY_VIOLATION",
                                  "reviewerId":77001
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("revId").asLong();
    }
}
