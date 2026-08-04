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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * acknowledge-bias + bias-override 통합 테스트.
 *
 * 시나리오:
 *   10) run() → BIAS_REVIEWING
 *   20) 리포트 없이 acknowledge-bias → 422 (LOAN_193)
 *   21) bias-report 수신 (severity=BLOCKED)
 *   22) BLOCKED 상태에서 acknowledge-bias → 422 (LOAN_194)
 *   23) bias-override (상급자 우회) → biasOverrideBy 기록
 *   24) override 후 acknowledge-bias → 200, PENDING_APPROVER
 *   30) 다른 신청: 리포트 HIGH → acknowledge-bias 바로 성공
 *   40) 잘못된 상태(PENDING_APPROVER)에서 acknowledge-bias → 422 (LOAN_192)
 *
 * 날짜 격리: 연도 2035 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewAcknowledgeBiasTest extends AbstractLoanIntegrationTest {

    private static Long prodId;
    private static Long applId;
    private static Long revId;
    private static Long applId2;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);

        applId = createApplication(prodId, 20350001L);
        prepFullyEligible(applId);

        applId2 = createApplication(prodId, 20350002L);
        prepFullyEligible(applId2);
    }

    @Test @Order(10)
    void run_후_BIAS_REVIEWING() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"APPROVED","reviewerId":20350101}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andReturn();
        revId = extractData(r).get("revId").asLong();
    }

    @Test @Order(20)
    void 리포트_없이_acknowledge_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_193"));
    }

    @Test @Order(21)
    void bias_report_BLOCKED_수신() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", revId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"BLOCKED",
                                  "summary":"심각한 연령 차별 패턴 감지",
                                  "findings":[{"code":"AGE_BIAS","result":"SEVERE","detail":"직접 차별 의심"}],
                                  "model":"claude-opus-4-7","modelVersion":"20250514",
                                  "promptHash":"xxx","inputToken":1000,"outputToken":300,"latencyMs":1200
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test @Order(22)
    void BLOCKED_상태에서_acknowledge_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_194"));
    }

    @Test @Order(23)
    void bias_override_상급자_우회() throws Exception {
        // 우회 승인자는 바디가 아닌 인증 토큰(X-User-Id)에서 식별한다.
        // run() 의 심사자는 기본 토큰(X-User-Id=1)이므로, 4-eye 를 위해 다른 직원으로 우회한다.
        mockMvc.perform(post("/api/loan-reviews/{revId}/bias-override", revId)
                        .header("X-User-Id", "20350901")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overrideReason":"규정 검토 후 수용 가능 판단"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.biasOverrideBy").value(20350901))
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"));
    }

    @Test @Order(24)
    void override_후_acknowledge_성공_PENDING_APPROVER() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"acknowledgeRemark":"상급자 승인 확인 후 진행"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVER"));
    }

    @Test @Order(30)
    void HIGH_severity_acknowledge_바로_성공() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"APPROVED","reviewerId":20350102}
                                """))
                .andExpect(status().isCreated()).andReturn();
        Long rev2 = extractData(r).get("revId").asLong();

        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", rev2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"HIGH",
                                  "summary":"경미한 패턴 감지",
                                  "findings":[{"code":"AGE_BIAS","result":"LOW_SIGNAL","detail":null}],
                                  "model":"claude-opus-4-7","modelVersion":"20250514",
                                  "promptHash":"yyy","inputToken":800,"outputToken":200,"latencyMs":900
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVER"))
                .andExpect(jsonPath("$.data.biasSeverityCd").value("HIGH"));
    }

    @Test @Order(40)
    void PENDING_APPROVER_에서_acknowledge_재호출_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_192"));
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Long createCreditProduct() throws Exception {
        String code = "ACK_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"편향확인 신용대출","loanTypeCd":"CREDIT",
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
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112035"}
                                """))
                .andExpect(status().isCreated());
    }
}
