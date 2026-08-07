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
 * 운영 도구(bias-ops-note, expire-bias-reviewing) 통합 테스트.
 *
 * 시나리오:
 *   10) applId1: run() → BIAS_REVIEWING
 *   11) BIAS_REVIEWING 아닌 상태에서 ops-note → 422 (LOAN_192)
 *   12) bias-report BLOCKED 수신
 *   13) BLOCKED 상태에서 bias-ops-note → 200, biasSeverityCd=NONE
 *   14) ops-note 후 acknowledge-bias 성공 → PENDING_APPROVER
 *
 *   20) applId2: run() → expire-bias-reviewing(olderThanDays=0) → EXPIRED
 *   21) expire 대상 없을 때 → processed=0
 *
 * 날짜 격리: 연도 2037 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewOpsToolsTest extends AbstractLoanIntegrationTest {

    private static Long prodId;
    private static Long applId1;
    private static Long revId1;
    private static Long applId2;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);

        applId1 = createApplication(prodId, 20370001L);
        prepFullyEligible(applId1);

        applId2 = createApplication(prodId, 20370002L);
        prepFullyEligible(applId2);
    }

    @Test @Order(10)
    void applId1_run_후_BIAS_REVIEWING() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"APPROVED","reviewerId":20370101}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andReturn();
        revId1 = extractData(r).get("revId").asLong();
    }

    @Test @Order(11)
    void 존재하지_않는_revId_ops_note_404() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-ops-note", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opsStaffId":20370901,"note":"테스트용 없는 건"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test @Order(12)
    void bias_report_BLOCKED_수신() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", revId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"BLOCKED",
                                  "summary":"심각한 차별 패턴",
                                  "findings":[{"code":"GENDER_BIAS","result":"SEVERE","detail":"직접 차별"}],
                                  "model":"claude-opus-4-7","modelVersion":"20250514",
                                  "promptHash":"blk","inputToken":900,"outputToken":300,"latencyMs":1100
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test @Order(13)
    void bias_ops_note_주입_BLOCKED_해제() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-ops-note", revId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opsStaffId":20370901,"note":"규정 검토: 해당 건은 연령 무관 거절 사유 확인됨. 편향 차단 해제."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adviceTypeCd").value("BIAS_CHECK"))
                .andExpect(jsonPath("$.data.severityCd").value("NONE"))
                .andExpect(jsonPath("$.data.revId").value(revId1));
    }

    @Test @Order(14)
    void ops_note_후_acknowledge_성공() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review/acknowledge-bias", applId1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVER"))
                .andExpect(jsonPath("$.data.biasSeverityCd").value("NONE"));
    }

    @Test @Order(20)
    void expire_bias_reviewing_대상_만료() throws Exception {
        // applId2 run → BIAS_REVIEWING 상태로 준비
        MvcResult runResult = mockMvc.perform(post("/api/loan-applications/{id}/review", applId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revTypeCd":"MANUAL","revDecisionCd":"APPROVED","reviewerId":20370102}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andReturn();
        long revId = extractData(runResult).get("revId").asLong();

        // olderThanDays=0 → 지금 이전 reviewedAt 인 BIAS_REVIEWING 전부 만료.
        // DB 컨테이너를 여러 테스트 클래스가 공유하므로 다른 클래스가 남긴 BIAS_REVIEWING
        // 도 함께 걸린다. 전체 건수가 아니라 "이 테스트가 만든 건이 포함됐는지"를 본다.
        mockMvc.perform(post("/api/internal/loan-reviews/expire-bias-reviewing")
                        .param("olderThanDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiredRevIds").isArray())
                .andExpect(jsonPath("$.data.expiredRevIds[?(@ == %d)]".formatted(revId)).exists());
    }

    @Test @Order(21)
    void expire_대상_없을때_processed_0() throws Exception {
        // 이미 모두 만료됐으므로 다시 호출하면 0
        mockMvc.perform(post("/api/internal/loan-reviews/expire-bias-reviewing")
                        .param("olderThanDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(0));
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Long createCreditProduct() throws Exception {
        String code = "OPS_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"운영도구 신용대출","loanTypeCd":"CREDIT",
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
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112037"}
                                """))
                .andExpect(status().isCreated());
    }
}
