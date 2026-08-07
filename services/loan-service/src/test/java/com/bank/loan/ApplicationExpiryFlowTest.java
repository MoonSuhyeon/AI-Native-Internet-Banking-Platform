package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품별 applicationValidityDays 차등 만료 회귀 (Plan 09).
 *
 * 격리 연도: 2039 — loan_review.approved_at 을 2039-01-01 로 고정하여
 * 다른 테스트의 2026년 레코드와 분리.
 *
 * 시나리오:
 *   10) baseDate=20390109 (8d 경과) → prodA(7d) 만료, prodB(null=14d) 유지
 *   11) applA=EXPIRED / applB=APPROVED 상태 확인
 *   12) baseDate=20390116 (15d 경과) → prodB(14d) 만료
 *   13) applB=EXPIRED 상태 확인
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationExpiryFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long applIdA; // 상품 validityDays=7
    private Long applIdB; // 상품 validityDays=null → 기본 14

    @BeforeAll
    void setup() throws Exception {
        Long prodIdA = createProduct("EXP_A_" + uniq(), 7);
        Long prodIdB = createProduct("EXP_B_" + uniq(), null);

        applIdA = createAndApprove(prodIdA);
        applIdB = createAndApprove(prodIdB);

        // approved_at 을 2039-01-01 로 고정 (격리)
        jdbcTemplate.update(
                "UPDATE loan_review SET approved_at = '2039-01-01 00:00:00+00' WHERE appl_id = ?",
                applIdA);
        jdbcTemplate.update(
                "UPDATE loan_review SET approved_at = '2039-01-01 00:00:00+00' WHERE appl_id = ?",
                applIdB);
    }

    // ============================================================
    // 시나리오
    // ============================================================

    @Test @Order(10)
    void 배치_8일_후_validityDays7인_prodA만_만료() throws Exception {
        // 2039-01-09: threshold_A = 2039-01-02 > approvedAt(2039-01-01) → A 만료
        //             threshold_B = 2038-12-26 < approvedAt(2039-01-01) → B 유지
        MvcResult r = mockMvc.perform(post("/api/internal/application-expiry/run")
                        .param("baseDate", "20390109"))
                .andExpect(status().isOk())
                .andReturn();
        int processed = extractData(r).get("processed").asInt();
        // applA 포함 최소 1건 이상 만료 (공유 DB 에 2026년 APPROVED 건도 포함될 수 있음)
        assertThat(processed).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(11)
    void applA_EXPIRED_applB_APPROVED() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}", applIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("EXPIRED"));

        mockMvc.perform(get("/api/loan-applications/{applId}", applIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("APPROVED"));
    }

    @Test @Order(12)
    void 배치_15일_후_validityDaysNull인_prodB도_만료() throws Exception {
        // 2039-01-16: threshold_B = 2039-01-02 > approvedAt(2039-01-01) → B 만료
        // 이 시점에 APPROVED 남은 건은 applB 하나뿐 (시나리오 10 에서 나머지 전부 만료됨)
        mockMvc.perform(post("/api/internal/application-expiry/run")
                        .param("baseDate", "20390116"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));
    }

    @Test @Order(13)
    void applB_EXPIRED() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}", applIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("EXPIRED"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct(String prodCd, Integer validityDays) throws Exception {
        String validityField = validityDays == null
                ? ""
                : ", \"applicationValidityDays\":" + validityDays;
        String body = """
                {
                  "prodCd":"%s", "prodName":"만료테스트상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":500,
                  "minAmount":1000000, "maxAmount":50000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false%s
                }
                """.formatted(prodCd, validityField);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long prodId = extractData(r).get("prodId").asLong();
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prodStatusCd":"ACTIVE" }
                                """))
                .andExpect(status().isOk());
        return prodId;
    }

    private Long createAndApprove(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":9039, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":20000000, "requestedPeriodMo":24,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long applId = extractData(r).get("applId").asLong();

        // 가심사 → CB → DSR → IDV → 본심사 APPROVED
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedGrade":"BBB", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE",
                                  "cevalScore":700, "evalLimitAmount":50000000 }
                                """))
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
        mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated());

        return applId;
    }
}
