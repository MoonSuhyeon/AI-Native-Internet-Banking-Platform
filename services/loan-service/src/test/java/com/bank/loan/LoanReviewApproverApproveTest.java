package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 승인자 최종 확정 통합 테스트.
 *
 * 시나리오:
 *   10) applId1: 전체 흐름 → PENDING_APPROVER 상태로 준비
 *   11) applId2: 전체 흐름 → PENDING_APPROVER 상태로 준비
 *   20) GET /pending-approver → 2건 반환
 *   30) 같은 사람(reviewerId) 승인 시도 → 422 LOAN_196
 *   31) OVERRIDE_APPROVED인데 overrideReasonCd 없음 → 422 LOAN_197
 *   32) OVERRIDE_APPROVED인데 금액 없음 → 422 LOAN_198
 *   33) PENDING_APPROVER 아닌 상태 → 422 LOAN_195 (별도 신청)
 *   40) applId1: APPROVE_AS_IS → COMPLETED, 신청 APPROVED
 *   41) applId2: OVERRIDE_REJECTED → COMPLETED, 신청 REJECTED
 *   50) 완료 후 pending-approver 목록 비어있음
 *
 * 날짜 격리: 연도 2036 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewApproverApproveTest extends AbstractLoanIntegrationTest {

    private static Long prodId;
    private static Long applId1;
    private static Long applId2;
    private static Long applId3;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);

        applId1 = createApplication(prodId, 20360001L);
        prepFullyEligible(applId1);

        applId2 = createApplication(prodId, 20360002L);
        prepFullyEligible(applId2);

        applId3 = createApplication(prodId, 20360003L);
        prepFullyEligible(applId3);
    }

    @Test @Order(10)
    void applId1_PENDING_APPROVER_준비() throws Exception {
        preparePendingApprover(applId1, 20360101L, "APPROVED");
    }

    @Test @Order(11)
    void applId2_PENDING_APPROVER_준비() throws Exception {
        preparePendingApprover(applId2, 20360102L, "APPROVED");
    }

    @Test @Order(20)
    void pending_approver_목록_2건() throws Exception {
        // 전역 개수를 단언하지 않는다 — 통합 테스트가 Postgres 컨테이너를 공유하므로
        // 다른 테스트 클래스가 만든 PENDING_APPROVER 건이 함께 조회된다.
        // 이 클래스가 준비한 두 건이 목록에 있는지만 확인한다.
        mockMvc.perform(get("/api/loan-reviews/pending-approver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.applId == %d)]".formatted(applId1)).exists())
                .andExpect(jsonPath("$.data[?(@.applId == %d)]".formatted(applId2)).exists());
    }

    @Test @Order(30)
    void 동일_심사원이_승인자로_시도_4eye_위반_422() throws Exception {
        // 4-eye 판정은 인증 주체 기준 — applId1 의 심사원(20360101) 본인이 호출하면 위반
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId1)
                        .header("X-User-Id", "20360101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverDecisionCd":"APPROVE_AS_IS"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_196"));
    }

    @Test @Order(31)
    void OVERRIDE_APPROVED_사유없음_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverDecisionCd":"OVERRIDE_APPROVED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_197"));
    }

    @Test @Order(32)
    void OVERRIDE_APPROVED_금액없음_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approverDecisionCd":"OVERRIDE_APPROVED",
                                  "overrideReasonCd":"RISK_ADJUSTMENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_198"));
    }

    @Test @Order(33)
    void PENDING_APPROVER_아닌_상태_422() throws Exception {
        // applId3은 아직 BIAS_REVIEWING (run만 했고 acknowledge 안 함)
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"APPROVED","reviewerId":20360103}
                                """))
                .andExpect(status().isCreated()).andReturn();
        Long rev3 = extractData(r).get("revId").asLong();
        // bias-report 적재 없이 바로 approver-approve 시도
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverDecisionCd":"APPROVE_AS_IS"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_195"));
    }

    @Test @Order(40)
    void applId1_APPROVE_AS_IS_최종_승인() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverDecisionCd":"APPROVE_AS_IS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.approvedDecisionCd").value("APPROVE_AS_IS"))
                // approverId 는 요청 바디가 아니라 인증 주체(기본 X-User-Id=1)로 기록된다
                .andExpect(jsonPath("$.data.approverId").value(1))
                .andExpect(jsonPath("$.data.approvedAt").isNotEmpty());

        // 신청 상태 APPROVED 전이 확인
        mockMvc.perform(get("/api/loan-applications/{id}", applId1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("APPROVED"));
    }

    @Test @Order(41)
    void applId2_OVERRIDE_REJECTED_최종_거절() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approverDecisionCd":"OVERRIDE_REJECTED",
                                  "overrideReasonCd":"RISK_ADJUSTMENT",
                                  "overrideRemark":"추가 위험 요인 발견",
                                  "overrideRejectReasonCd":"HIGH_RISK"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.approvedDecisionCd").value("OVERRIDE_REJECTED"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("REJECTED"))
                .andExpect(jsonPath("$.data.overrideReasonCd").value("RISK_ADJUSTMENT"))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());

        // 신청 상태 REJECTED 전이 확인
        mockMvc.perform(get("/api/loan-applications/{id}", applId2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("REJECTED"));
    }

    @Test @Order(50)
    void 완료_후_pending_approver_목록_비어있음() throws Exception {
        // 마찬가지로 전역 개수가 아니라, 이 클래스가 확정한 두 건이 목록에서
        // 빠졌는지를 확인한다.
        mockMvc.perform(get("/api/loan-reviews/pending-approver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.applId == %d)]".formatted(applId1)).doesNotExist())
                .andExpect(jsonPath("$.data[?(@.applId == %d)]".formatted(applId2)).doesNotExist());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private void preparePendingApprover(Long applId, long reviewerId, String decisionCd) throws Exception {
        // 심사원은 요청 바디가 아니라 인증 주체에서 결정된다(LoanReviewService: reviewerId(actorId)).
        // RunReviewRequest 에는 reviewerId 필드 자체가 없으므로 X-User-Id 로 지정해야 한다.
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId)
                        .header("X-User-Id", String.valueOf(reviewerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"%s"}
                                """.formatted(decisionCd)))
                .andExpect(status().isCreated()).andReturn();
        Long revId = extractData(r).get("revId").asLong();

        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", revId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"LOW","summary":"이상 없음","findings":[],
                                  "model":"claude-opus-4-7","modelVersion":"20250514",
                                  "promptHash":"ok","inputToken":500,"outputToken":100,"latencyMs":600
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .header("X-User-Id", String.valueOf(reviewerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVER"));
    }

    private Long createCreditProduct() throws Exception {
        String code = "APV_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"승인자확정 신용대출","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":false,"guarantorRequiredYn":false
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{id}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prodStatusCd":"ACTIVE"}
                                """))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId, long customerId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":%d,"prodId":%d,"channelCd":"MOBILE",
                                  "requestedAmount":30000000,"requestedPeriodMo":36,
                                  "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(customerId, prodId)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void prepFullyEligible(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prescResultCd":"PASS","estimatedScore":700}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cevalEngine":"KCB","cevalDecisionCd":"APPROVE",
                                  "cevalScore":700,"evalLimitAmount":50000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annualIncomeAmt":80000000,"newAnnualRepayAmt":10000000}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112036"}
                                """))
                .andExpect(status().isCreated());
    }
}
