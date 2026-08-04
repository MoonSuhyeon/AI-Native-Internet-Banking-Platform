package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이상거래 본사 상신(escalate-to-hq) + HQ 담당자 목록 조회 통합 테스트.
 *
 * 시나리오:
 *   10) 심사를 PENDING_APPROVER 상태까지 준비
 *   20) 지점장이 상신 → revStatusCd = ESCALATED_TO_HQ, escalatedAt 존재
 *   30) 이미 상신된 건 재상신 → 409 LOAN_203
 *   40) HQ 담당자 상신 목록 조회 → 1건 포함
 *   50) COMPLETED 건 상신 시도 → 422 LOAN_204
 *
 * 날짜 격리: customerId 기반 연도 2037 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewEscalateToHqTest extends AbstractLoanIntegrationTest {

    private static Long prodId;
    private static Long applId;
    private static Long applIdCompleted;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);

        applId = createApplication(prodId, 20370001L);
        prepFullyEligible(applId);

        applIdCompleted = createApplication(prodId, 20370002L);
        prepFullyEligible(applIdCompleted);
    }

    @Test @Order(10)
    void applId_PENDING_APPROVER_준비() throws Exception {
        preparePendingApprover(applId, 20370101L, "APPROVED");
    }

    @Test @Order(11)
    void applIdCompleted_완료까지_준비() throws Exception {
        preparePendingApprover(applIdCompleted, 20370102L, "APPROVED");
        mockMvc.perform(post("/api/loan-applications/{id}/review/approver-approve", applIdCompleted)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverDecisionCd":"APPROVE_AS_IS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("COMPLETED"));
    }

    @Test @Order(20)
    void 지점장_상신_성공() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/escalate-to-hq", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"escalateReason":"비정상적인 서류 패턴 발견 — 본사 검토 요청"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("ESCALATED_TO_HQ"));
    }

    @Test @Order(30)
    void 이미_상신된_건_재상신_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/escalate-to-hq", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"escalateReason":"재시도"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_203"));
    }

    @Test @Order(40)
    void HQ_담당자_상신_목록_조회_1건_이상() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/escalated")
                        .with(SecurityMockMvcRequestPostProcessors.user("hq@bank.com")
                                .roles("HQ_REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test @Order(50)
    void COMPLETED_건_상신_불가_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/escalate-to-hq", applIdCompleted)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"escalateReason":"완료 건 상신 시도"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_204"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Long createCreditProduct() throws Exception {
        String code = "ESC_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"상신테스트 신용대출","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":"N","guarantorRequiredYn":"N"
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
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112037"}
                                """))
                .andExpect(status().isCreated());
    }

    private void preparePendingApprover(Long applId, long reviewerId, String decision) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revTypeCd":"MANUAL","revDecisionCd":"%s",
                                  "reviewerId":%d,"approvedAmount":30000000,
                                  "approvedRateBps":500,"approvedPeriodMo":36
                                }
                                """.formatted(decision, reviewerId)))
                .andExpect(status().isCreated());

        // bias-check disabled → 바로 PENDING_APPROVER 로 전이되지 않으므로 acknowledge-bias 호출
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
