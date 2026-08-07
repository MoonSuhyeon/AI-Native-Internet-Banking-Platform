package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본심사 자동 결정(권고) + 사람 확정 통합 테스트.
 *
 * 자동 권고 단계 (auto-decide):
 *   10) 자동 권고 APPROVED — CB.APPROVE + DSR.PASS (PENDING_APPROVAL, 신청 상태 전이 X)
 *   11) 자동 권고 REJECTED — CB.REJECT (reason=CB_REJECT)
 *   12) 자동 권고 REJECTED — DSR.FAIL (reason=DSR_OVER)
 *   13) 자동 권고 REJECTED — 담보 필수 + LTV.FAIL (reason=LTV_FAIL)
 *   14) 권고 불가 — CB.REVIEW → 422 LOAN_048
 *   15) 권고 불가 — 본심사 이미 수행 → 409 LOAN_039
 *   16) 권고 불가 — 미존재 applId → 404 LOAN_012
 *   17) 권고 불가 — CB 미수행(데이터 부족) → 422 LOAN_038
 *
 * 사람 확정 단계 (confirm):
 *   20) APPROVED 권고 확정 → COMPLETED + approvedAt + reviewerId, 신청 APPROVED 전이
 *   21) REJECTED 권고 확정 → COMPLETED + reviewerId, 신청 REJECTED 전이
 *   22) 이미 확정된 본심사 재 confirm → 422 LOAN_049
 *   23) 수동 본심사(처음부터 COMPLETED)에 confirm → 422 LOAN_049
 *   24) 본심사 없는 신청 confirm → 404 LOAN_042
 *
 * 권고 목록 (pending):
 *   30) GET /api/loan-reviews/pending — 확정 안 된 권고만 포함, 확정된 본심사·수동 본심사 미포함
 *
 * 권고 만료 배치 (expire-pending):
 *   40) cutoff 미해당(olderThanDays=99) → 0건 만료
 *   41) 강제 만료(olderThanDays=0) → 남은 PENDING 권고 전부 EXPIRED
 *   42) 만료된 권고 confirm 시도 → 422 LOAN_049
 *   43) GET pending → 빈 응답 (EXPIRED 미포함)
 *   44) olderThanDays 음수 → 400
 *
 * 통계 (stats):
 *   50) 오늘 포함 기간 stats — totalCount ≥ 본 클래스 적재 row 수, byTypeDecision/byStatus/byRejectReason 키 포함
 *   51) 과거 기간 stats (data 없음) — totalCount 0, empty maps
 *   52) from/to 형식 오류(yyyy-MM-dd) → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewAutoDecideFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 30_000_000L;
    private static final int  MONTHS  = 36;
    private static final int  BASE_BPS = 500;

    private Long creditProdId;
    private Long mortgageProdId;

    private Long approveApplId;       // CB.APPROVE + DSR.PASS → 자동 APPROVED
    private Long cbRejectApplId;      // CB.REJECT → 자동 REJECTED
    private Long dsrFailApplId;       // DSR.FAIL → 자동 REJECTED
    private Long ltvFailApplId;       // 담보 LTV.FAIL → 자동 REJECTED
    private Long cbReviewApplId;      // CB.REVIEW → 422 LOAN_048
    private Long alreadyReviewedAppl; // 이미 본심사 → 409
    private Long noCevalApplId;       // CB 미수행 → 422 LOAN_038

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        creditProdId = createCreditProduct();
        activateProduct(creditProdId);
        mortgageProdId = createMortgageProduct();
        activateProduct(mortgageProdId);

        approveApplId       = createApplication(creditProdId, 13001);
        cbRejectApplId      = createApplication(creditProdId, 13002);
        dsrFailApplId       = createApplication(creditProdId, 13003);
        cbReviewApplId      = createApplication(creditProdId, 13004);
        alreadyReviewedAppl = createApplication(creditProdId, 13005);
        noCevalApplId       = createApplication(creditProdId, 13006);
        ltvFailApplId       = createApplication(mortgageProdId, 13007);

        // CB.APPROVE + DSR.PASS (정상)
        prepEligible(approveApplId, "APPROVE", 50_000_000L, /*dsrFail*/ false);

        // CB.REJECT
        prepEligible(cbRejectApplId, "REJECT", 50_000_000L, /*dsrFail*/ false);

        // DSR.FAIL
        prepEligible(dsrFailApplId, "APPROVE", 50_000_000L, /*dsrFail*/ true);

        // CB.REVIEW
        prepEligible(cbReviewApplId, "REVIEW", 50_000_000L, /*dsrFail*/ false);

        // 이미 본심사 — 정상 신청 + 본심사 1회 수동 실행
        prepEligible(alreadyReviewedAppl, "APPROVE", 50_000_000L, /*dsrFail*/ false);
        runReviewApprovedManually(alreadyReviewedAppl);

        // CB 미수행 — 가심사만
        runPrescreening(noCevalApplId);

        // 담보 LTV FAIL
        prepEligible(ltvFailApplId, "APPROVE", 200_000_000L, /*dsrFail*/ false);
        Long col = createCollateral(ltvFailApplId);
        // applied 30M / requested 30M → ratio 10000 > 7000 → LTV FAIL
        evaluateCollateral(col, 30_000_000L);
        runLtv(col);
    }

    @Test @Order(10)
    void 권고_APPROVED_PENDING_APPROVAL() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", approveApplId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applId").value(approveApplId))
                .andExpect(jsonPath("$.data.revTypeCd").value("AUTO"))
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.data.approvedRateBps").value(BASE_BPS))
                .andExpect(jsonPath("$.data.approvedPeriodMo").value(MONTHS))
                // 권고 단계엔 approvedAt/reviewerId 없음 — confirm 시점에 채워짐
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist())
                .andExpect(jsonPath("$.data.reviewerId").doesNotExist())
                .andExpect(jsonPath("$.data.rejectReasonCd").doesNotExist());
    }

    @Test @Order(11)
    void 권고_REJECTED_CB_REJECT() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", cbRejectApplId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("CB_REJECT"))
                .andExpect(jsonPath("$.data.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test @Order(12)
    void 권고_REJECTED_DSR_FAIL() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", dsrFailApplId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("DSR_OVER"));
    }

    @Test @Order(13)
    void 권고_REJECTED_LTV_FAIL_담보필수() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", ltvFailApplId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("LTV_FAIL"));
    }

    @Test @Order(14)
    void 자동_결정_불가_CB_REVIEW_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", cbReviewApplId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_048"));
    }

    @Test @Order(15)
    void 본심사_이미_수행_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", alreadyReviewedAppl))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_039"));
    }

    @Test @Order(16)
    void 미존재_applId_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(17)
    void CB_미수행_데이터부족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review/auto-decide", noCevalApplId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(20)
    void 권고_APPROVED_확정_후_BIAS_REVIEWING() throws Exception {
        // confirm 은 이제 BIAS_REVIEWING 으로 전이 (승인자 단계 도입으로 COMPLETED 아님)
        // approveApplId 는 시나리오 10 에서 PENDING_APPROVAL APPROVED 권고 상태
        // 확정 심사관은 바디가 아닌 인증 토큰(X-User-Id)에서만 식별한다.
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", approveApplId)
                        .header("X-User-Id", "91001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmRemark":"권고 그대로 확정" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerId").value(91001))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test @Order(21)
    void 권고_REJECTED_확정_후_BIAS_REVIEWING() throws Exception {
        // cbRejectApplId 는 시나리오 11 에서 PENDING_APPROVAL REJECTED 권고 상태
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", cbRejectApplId)
                        .header("X-User-Id", "91002")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("CB_REJECT"))
                .andExpect(jsonPath("$.data.reviewerId").value(91002))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test @Order(22)
    void 이미_확정된_본심사_재confirm_422() throws Exception {
        // 시나리오 20 에서 approveApplId 가 이미 BIAS_REVIEWING — PENDING_APPROVAL 아님
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_049"));
    }

    @Test @Order(23)
    void 수동_본심사_confirm_불가_422() throws Exception {
        // alreadyReviewedAppl 는 setup 에서 수동 본심사로 즉시 COMPLETED 됨
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", alreadyReviewedAppl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_049"));
    }

    @Test @Order(24)
    void 본심사_없는_신청_confirm_404() throws Exception {
        // noCevalApplId 는 본심사 시도 자체가 422 라 본심사 row 없음
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", noCevalApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_042"));
    }

    @Test @Order(40)
    void expire_cutoff_미해당_0건() throws Exception {
        // olderThanDays=99 → cutoff 가 과거 — 현재 권고 어떤 것도 그 이전이 아니므로 0건
        mockMvc.perform(post("/api/internal/loan-reviews/expire-pending")
                        .param("olderThanDays", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.expiredRevIds.length()").value(0))
                .andExpect(jsonPath("$.data.cutoffAt").exists());
    }

    @Test @Order(41)
    void expire_강제_만료_PENDING_전부_EXPIRED() throws Exception {
        // olderThanDays=0 → cutoff=now → 모든 reviewedAt 이 cutoff 이전인 PENDING 권고 만료
        // 이 시점 PENDING: dsrFailApplId, ltvFailApplId 권고 (시나리오 30 이후 그대로)
        mockMvc.perform(post("/api/internal/loan-reviews/expire-pending")
                        .param("olderThanDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.cutoffAt").exists());
    }

    @Test @Order(42)
    void 만료된_권고_confirm_시도_422() throws Exception {
        // 시나리오 41 에서 dsrFailApplId 권고 가 EXPIRED 됨
        mockMvc.perform(post("/api/loan-applications/{applId}/review/confirm", dsrFailApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_049"));
    }

    @Test @Order(43)
    void GET_pending_EXPIRED_미포함() throws Exception {
        // 시나리오 41 후 PENDING 잔존 권고 0 (또는 이 클래스 외 PENDING 없음)
        // — 따라서 본 클래스가 만든 PENDING 권고가 응답에서 사라져 있어야 함
        mockMvc.perform(get("/api/loan-reviews/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].applId",
                        not(hasItem(dsrFailApplId.intValue()))))
                .andExpect(jsonPath("$.data[*].applId",
                        not(hasItem(ltvFailApplId.intValue()))));
    }

    @Test @Order(44)
    void expire_olderThanDays_음수_400() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/expire-pending")
                        .param("olderThanDays", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(50)
    void stats_오늘기간_응답_schema_검증() throws Exception {
        // 오늘 날짜 기준 wide range — 본 클래스가 적재한 본심사 row 가 모두 포함된 응답이어야 함.
        java.time.LocalDate today = java.time.LocalDate.now();
        String from = today.minusDays(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String to   = today.plusDays(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        mockMvc.perform(get("/api/loan-reviews/stats")
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value(from))
                .andExpect(jsonPath("$.data.to").value(to))
                .andExpect(jsonPath("$.data.totalCount",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.byTypeDecision").exists())
                .andExpect(jsonPath("$.data.byStatus").exists())
                .andExpect(jsonPath("$.data.byRejectReason").exists())
                // 본 클래스가 적재한 사유들 — 최소 한 건 이상 포함되어야 함
                .andExpect(jsonPath("$.data.byRejectReason.CB_REJECT",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test @Order(51)
    void stats_과거기간_데이터없음_0건() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/stats")
                        .param("from", "19990101").param("to", "19990131"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.byTypeDecision.length()").value(0))
                .andExpect(jsonPath("$.data.byStatus.length()").value(0))
                .andExpect(jsonPath("$.data.byRejectReason.length()").value(0));
    }

    @Test @Order(52)
    void stats_날짜형식_오류_400() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/stats")
                        .param("from", "2026-05-01").param("to", "2026-05-21"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(30)
    void GET_pending_권고만_포함_확정본심사_미포함() throws Exception {
        // 이 시점 상태:
        //   PENDING_APPROVAL: dsrFailApplId(시나리오 12), ltvFailApplId(시나리오 13)
        //   COMPLETED:        approveApplId(시나리오 20 확정), cbRejectApplId(시나리오 21 확정),
        //                     alreadyReviewedAppl(setup 에서 수동 본심사)
        mockMvc.perform(get("/api/loan-reviews/pending"))
                .andExpect(status().isOk())
                // 권고가 응답에 포함
                .andExpect(jsonPath("$.data[*].applId", hasItem(dsrFailApplId.intValue())))
                .andExpect(jsonPath("$.data[*].applId", hasItem(ltvFailApplId.intValue())))
                // 확정된 자동/수동 본심사는 응답에 미포함
                .andExpect(jsonPath("$.data[*].applId", not(hasItem(approveApplId.intValue()))))
                .andExpect(jsonPath("$.data[*].applId", not(hasItem(cbRejectApplId.intValue()))))
                .andExpect(jsonPath("$.data[*].applId", not(hasItem(alreadyReviewedAppl.intValue()))))
                // 응답 element 의 revStatusCd 가 모두 PENDING_APPROVAL
                .andExpect(jsonPath("$.data[*].revStatusCd",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PENDING_APPROVAL"))));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createCreditProduct() throws Exception {
        String code = "AUD_C_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"자동결정 신용대출", "loanTypeCd":"CREDIT",
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
        String code = "AUD_M_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"자동결정 담보대출", "loanTypeCd":"MORTGAGE",
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

    private void runCeval(Long applId, String decision, long evalLimit) throws Exception {
        String body = """
                {
                  "cevalEngine":"KCB", "cevalDecisionCd":"%s", "cevalScore":700,
                  "evalLimitAmount":%d
                }
                """.formatted(decision, evalLimit);
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void runDsr(Long applId, boolean fail) throws Exception {
        // PASS: 연소득 80M / 신규 원리금 10M → ratio 1250 < 4000
        // FAIL: 연소득 20M / 신규 원리금 15M → ratio 7500 > 4000
        String body = fail
                ? """
                  { "annualIncomeAmt":20000000, "newAnnualRepayAmt":15000000 }
                  """
                : """
                  { "annualIncomeAmt":80000000, "newAnnualRepayAmt":10000000 }
                  """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void prepEligible(Long applId, String cevalDecision, long evalLimit, boolean dsrFail)
            throws Exception {
        runPrescreening(applId);
        runCeval(applId, cevalDecision, evalLimit);
        runDsr(applId, dsrFail);
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
                  "colTypeCd":"REAL_ESTATE", "colName":"자동결정 담보",
                  "declaredValue":50000000, "currencyCd":"KRW", "ownershipTypeCd":"SOLE",
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
