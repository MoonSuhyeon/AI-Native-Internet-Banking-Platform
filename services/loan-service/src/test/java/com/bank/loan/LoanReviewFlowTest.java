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
 * 본심사 통합 테스트.
 *
 * 시나리오:
 *   10) APPROVED — CB+DSR 모두 통과 신청, 한도/금리/기간 자동 산정 + approvedAt 기록
 *   11) 동일 신청 재본심사 차단 → 409 LOAN_039
 *   12) GET 단건 조회 OK
 *   13) REJECTED — 수동 거절, rejectReasonCd 기록
 *   14) CB 미수행 신청 본심사 시도 → 422 LOAN_038
 *   15) CB REJECT 신청 본심사 시도 → 422 LOAN_038
 *   16) DSR FAIL 신청 본심사 시도 → 422 LOAN_038
 *   17) PRESCREENED 아닌 신청(SUBMITTED) 본심사 시도 → 422 LOAN_038
 *   18) GET 미존재 신청 → 404 LOAN_012
 *   19) GET 본심사 안 한 신청 → 404 LOAN_042
 *   20) revDecisionCd 누락 → 400
 *   21) APPROVED + 입력 override (한도/금리/기간 명시값 그대로 사용)
 *   22) APPROVED + CB.evalLimit 가 신청금액보다 작으면 그 값으로 한도 제한
 *   23) 담보 필수 상품 + LTV PASS → APPROVED 가능
 *   24) 담보 필수 상품 + 담보 무첨부 → 422 LOAN_038
 *   25) 담보 필수 상품 + LTV 미수행 담보 → 422 LOAN_038
 *   26) 담보 필수 상품 + LTV FAIL 담보 → 422 LOAN_038
 *   30) APPROVED → REJECTED 정정 → 신청 REJECTED, rejectReasonCd 갱신, approvedAt 제거
 *   31) REJECTED → APPROVED 정정 → 신청 APPROVED, 한도 자동 산정
 *   32) revisitReasonCd 누락 → 400 (DTO @NotBlank)
 *   33) 미존재 applId 정정 → 404 LOAN_012
 *   34) 본심사 없는 신청 정정 → 404 LOAN_042
 *   35) 약정 체결 후 정정 차단 → 422 LOAN_044
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanReviewFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 30_000_000L;
    private static final int  MONTHS  = 36;
    private static final int  BASE_BPS = 500;

    private Long prodId;
    private Long approveApplId;
    private Long rejectApplId;
    private Long noCevalApplId;
    private Long cevalRejectApplId;
    private Long dsrFailApplId;
    private Long submittedApplId;
    private Long pristineApplId;
    private Long overrideApplId;
    private Long capLimitApplId;

    // 담보 필수 상품(MORTGAGE) 시나리오
    private Long mortgageProdId;
    private Long mortgageOkApplId;
    private Long mortgageNoColApplId;
    private Long mortgageNoLtvApplId;
    private Long mortgageLtvFailApplId;

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);

        approveApplId   = createApplication(prodId);
        rejectApplId    = createApplication(prodId);
        noCevalApplId   = createApplication(prodId);
        cevalRejectApplId = createApplication(prodId);
        dsrFailApplId   = createApplication(prodId);
        submittedApplId = createApplication(prodId);
        pristineApplId  = createApplication(prodId);
        overrideApplId  = createApplication(prodId);
        capLimitApplId  = createApplication(prodId);

        // 정상 경로: 가심사 + CB(APPROVE) + DSR(PASS)
        prepFullyEligible(approveApplId, 50_000_000L);
        prepFullyEligible(rejectApplId, 50_000_000L);
        prepFullyEligible(pristineApplId, 50_000_000L);
        prepFullyEligible(overrideApplId, 50_000_000L);
        prepFullyEligible(capLimitApplId, 10_000_000L); // CB 한도 신청금액보다 낮음

        // CB 까지만, DSR 미수행
        runPrescreening(noCevalApplId, "PASS");

        // CB REJECT
        runPrescreening(cevalRejectApplId, "PASS");
        runCeval(cevalRejectApplId, "REJECT", 50_000_000L);

        // DSR FAIL
        runPrescreening(dsrFailApplId, "PASS");
        runCeval(dsrFailApplId, "APPROVE", 50_000_000L);
        runDsrFail(dsrFailApplId);

        // submittedApplId 는 가심사도 안 함 (SUBMITTED)

        // ----- 담보 필수 상품 시나리오 -----
        mortgageProdId = createMortgageProduct();
        activateProduct(mortgageProdId);

        mortgageOkApplId       = createApplication(mortgageProdId);
        mortgageNoColApplId    = createApplication(mortgageProdId);
        mortgageNoLtvApplId    = createApplication(mortgageProdId);
        mortgageLtvFailApplId  = createApplication(mortgageProdId);

        prepFullyEligible(mortgageOkApplId, 200_000_000L);
        prepFullyEligible(mortgageNoColApplId, 200_000_000L);
        prepFullyEligible(mortgageNoLtvApplId, 200_000_000L);
        prepFullyEligible(mortgageLtvFailApplId, 200_000_000L);

        // PASS LTV: applied 200M, lien 0, requested 30M → ratio 1500, max 140M → PASS
        Long passCol = createCollateral(mortgageOkApplId, 0L);
        evaluateCollateral(passCol, 200_000_000L);
        runLtv(passCol, null);

        // FAIL LTV: applied 30M, lien 0, requested 30M → ratio 10000 > 7000 → FAIL
        Long failCol = createCollateral(mortgageLtvFailApplId, 0L);
        evaluateCollateral(failCol, 30_000_000L);
        runLtv(failCol, null);

        // 평가만 있고 LTV 미수행
        Long noLtvCol = createCollateral(mortgageNoLtvApplId, 0L);
        evaluateCollateral(noLtvCol, 200_000_000L);

        // mortgageNoColApplId: 담보 자체 없음
    }

    @Test @Order(10)
    void APPROVED_자동산정() throws Exception {
        String body = """
                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/review", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applId").value(approveApplId))
                .andExpect(jsonPath("$.data.revStatusCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                // CB.evalLimit=50M >= req=30M, product max=100M → approved=30M
                .andExpect(jsonPath("$.data.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.data.approvedRateBps").value(BASE_BPS))
                .andExpect(jsonPath("$.data.approvedPeriodMo").value(MONTHS))
                .andExpect(jsonPath("$.data.approvedAt").exists());
    }

    @Test @Order(11)
    void 동일_신청_재본심사_차단_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_039"));
    }

    @Test @Order(12)
    void GET_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/review", approveApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applId").value(approveApplId))
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"));
    }

    @Test @Order(13)
    void REJECTED_수동_거절() throws Exception {
        String body = """
                {
                  "revTypeCd":"MANUAL",
                  "revDecisionCd":"REJECTED",
                  "rejectReasonCd":"POLICY_VIOLATION",
                  "revRemark":"내부 정책 위반"
                }
                """;
        // 심사원은 인증 주체에서 결정된다(RunReviewRequest 에 reviewerId 필드 없음).
        mockMvc.perform(post("/api/loan-applications/{applId}/review", rejectApplId)
                        .header("X-User-Id", "99001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("POLICY_VIOLATION"))
                .andExpect(jsonPath("$.data.reviewerId").value(99001))
                .andExpect(jsonPath("$.data.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test @Order(14)
    void CB_미수행_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", noCevalApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(15)
    void CB_REJECT_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", cevalRejectApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(16)
    void DSR_FAIL_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", dsrFailApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(17)
    void PRESCREENED_아님_SUBMITTED_상태_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", submittedApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(18)
    void GET_미존재_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/review", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(19)
    void GET_본심사_안한_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/review", pristineApplId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_042"));
    }

    @Test @Order(20)
    void revDecisionCd_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", pristineApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(21)
    void APPROVED_입력_override() throws Exception {
        String body = """
                {
                  "revTypeCd":"MANUAL",
                  "revDecisionCd":"APPROVED",
                  "approvedAmount":20000000,
                  "approvedRateBps":420,
                  "approvedPeriodMo":24
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/review", overrideApplId)
                        .header("X-User-Id", "99002")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.approvedAmount").value(20000000))
                .andExpect(jsonPath("$.data.approvedRateBps").value(420))
                .andExpect(jsonPath("$.data.approvedPeriodMo").value(24));
    }

    @Test @Order(22)
    void APPROVED_CB_한도로_제한() throws Exception {
        // CB.evalLimit=10M < req=30M → approved=10M
        mockMvc.perform(post("/api/loan-applications/{applId}/review", capLimitApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.approvedAmount").value(10000000));
    }

    @Test @Order(23)
    void 담보필수_LTV_PASS_APPROVED() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", mortgageOkApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"));
    }

    @Test @Order(24)
    void 담보필수_담보_무첨부_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", mortgageNoColApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(25)
    void 담보필수_LTV_미수행_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", mortgageNoLtvApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(26)
    void 담보필수_LTV_FAIL_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", mortgageLtvFailApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(30)
    void 정정_APPROVED를_REJECTED로() throws Exception {
        // approveApplId 는 시나리오 10 에서 APPROVED 상태
        String body = """
                {
                  "revDecisionCd":"REJECTED",
                  "rejectReasonCd":"POLICY_REVIEW",
                  "revRemark":"심사 정정 - 정책 재검토",
                  "revisitReasonCd":"APPEAL"
                }
                """;
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", approveApplId)
                        .header("X-User-Id", "99010")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("POLICY_REVIEW"))
                .andExpect(jsonPath("$.data.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist())
                .andExpect(jsonPath("$.data.reviewerId").value(99010));

        // 체크로그 정정 기록(FINAL_DECISION) 누적 — 자동 5건 + 정정 1건 = 6건
        Long revId = extractData(mockMvc.perform(
                        get("/api/loan-applications/{applId}/review", approveApplId))
                        .andReturn()).get("revId").asLong();
        mockMvc.perform(get("/api/loan-reviews/{revId}/checks", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[5].checkItemCd").value("FINAL_DECISION"))
                .andExpect(jsonPath("$.data[5].checkResultCd").value("FAIL"))
                .andExpect(jsonPath("$.data[5].checkRemark").value(
                        org.hamcrest.Matchers.containsString("revisit(APPEAL)")));
    }

    @Test @Order(31)
    void 정정_REJECTED를_APPROVED로_자동산정() throws Exception {
        // rejectApplId 는 시나리오 13 에서 REJECTED 상태
        String body = """
                {
                  "revDecisionCd":"APPROVED",
                  "revisitReasonCd":"NEW_EVIDENCE"
                }
                """;
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", rejectApplId)
                        .header("X-User-Id", "99011")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                // CB.evalLimit=50M >= req=30M, product max=100M → approved=30M
                .andExpect(jsonPath("$.data.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.data.approvedRateBps").value(BASE_BPS))
                .andExpect(jsonPath("$.data.approvedPeriodMo").value(MONTHS))
                .andExpect(jsonPath("$.data.approvedAt").exists())
                .andExpect(jsonPath("$.data.rejectReasonCd").doesNotExist());
    }

    @Test @Order(32)
    void 정정_revisitReasonCd_누락_400() throws Exception {
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(33)
    void 정정_미존재_applId_404() throws Exception {
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revDecisionCd":"APPROVED", "revisitReasonCd":"APPEAL" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(34)
    void 정정_본심사_없는_신청_404() throws Exception {
        // pristineApplId 는 시나리오 19 에서 본심사 없음 확인됨
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", pristineApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revDecisionCd":"APPROVED", "revisitReasonCd":"APPEAL" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_042"));
    }

    @Test @Order(35)
    void 정정_약정체결_후_차단_422() throws Exception {
        // overrideApplId 는 시나리오 21 에서 APPROVED(20M/420bps/24개월). 약정 체결까지 진행
        String contractBody = """
                {
                  "applId":%d,
                  "contractedAmount":20000000,
                  "contractedPeriodMo":24,
                  "baseRateBps":420,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL",
                  "cntrStartDate":"20260601"
                }
                """.formatted(overrideApplId);
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(contractBody))
                .andExpect(status().isCreated());

        // 약정 체결 후 신청 = CONTRACTED → 정정 차단
        mockMvc.perform(patch("/api/loan-applications/{applId}/review", overrideApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revDecisionCd":"REJECTED", "revisitReasonCd":"ERROR_CORRECTION" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_044"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "REV_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"본심사 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
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

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":8001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, AMOUNT, MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void runPrescreening(Long applId, String result) throws Exception {
        String body = "PASS".equals(result)
                ? """
                  { "prescResultCd":"PASS", "estimatedGrade":"BBB", "estimatedScore":700 }
                  """
                : """
                  { "prescResultCd":"REJECT", "rejectReasonCd":"LOW_INCOME" }
                  """;
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runCeval(Long applId, String decisionCd, long evalLimit) throws Exception {
        String body = """
                {
                  "cevalEngine":"KCB",
                  "cevalDecisionCd":"%s",
                  "cevalScore":700,
                  "evalLimitAmount":%d
                }
                """.formatted(decisionCd, evalLimit);
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runDsrPass(Long applId) throws Exception {
        // 연소득 80M, 신규 연 원리금 10M → ratio=1250 < 4000 → PASS
        String body = """
                {
                  "annualIncomeAmt":80000000,
                  "newAnnualRepayAmt":10000000
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runDsrFail(Long applId) throws Exception {
        // 연소득 20M, 신규 연 원리금 15M → ratio=7500 > 4000 → FAIL
        String body = """
                {
                  "annualIncomeAmt":20000000,
                  "newAnnualRepayAmt":15000000
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void prepFullyEligible(Long applId, long cevalLimit) throws Exception {
        runPrescreening(applId, "PASS");
        runCeval(applId, "APPROVE", cevalLimit);
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

    private Long createMortgageProduct() throws Exception {
        String code = "REVM_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"본심사+LTV 테스트", "loanTypeCd":"MORTGAGE",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":1000000000,
                  "minPeriodMo":12, "maxPeriodMo":360,
                  "collateralRequiredYn":"Y", "guarantorRequiredYn":"N"
                }
                """.formatted(code, BASE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private Long createCollateral(Long applId, Long seniorLien) throws Exception {
        String body = """
                {
                  "colTypeCd":"REAL_ESTATE",
                  "colName":"테스트 부동산",
                  "colAddress":"서울특별시 강남구",
                  "declaredValue":200000000,
                  "currencyCd":"KRW",
                  "ownershipTypeCd":"SOLE",
                  "seniorLienYn":"%s",
                  "seniorLienAmount":%d
                }
                """.formatted(seniorLien > 0 ? "Y" : "N", seniorLien);
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("colId").asLong();
    }

    private void evaluateCollateral(Long colId, Long appliedValue) throws Exception {
        String body = """
                {
                  "evalMethodCd":"APPRAISAL",
                  "evalAgencyCd":"KAB",
                  "appraisedValue":%d,
                  "appliedValue":%d,
                  "appliedStartDate":"20260101",
                  "appliedEndDate":"20271231"
                }
                """.formatted(appliedValue, appliedValue);
        mockMvc.perform(post("/api/collaterals/{colId}/evaluations", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runLtv(Long colId, Integer limitBpsOverride) throws Exception {
        String body = limitBpsOverride != null
                ? "{\"ltvLimitBps\":" + limitBpsOverride + "}"
                : "{}";
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
