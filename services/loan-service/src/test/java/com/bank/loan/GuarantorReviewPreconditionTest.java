package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보증 필수 상품 본심사 사전조건 회귀 테스트.
 *
 * 시나리오:
 *   10) 보증 필수(minCount=1) + SIGNED 보증인 1명 → 본심사 APPROVED 가능
 *   11) 보증 필수(minCount=1) + 보증인 없음         → 422 LOAN_038
 *   12) 보증 필수(minCount=1) + REGISTERED 만       → 422 LOAN_038 (미서명은 미충족)
 *   13) 보증 필수(minCount=2) + SIGNED 보증인 1명   → 422 LOAN_038 (수 부족)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuarantorReviewPreconditionTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT   = 30_000_000L;
    private static final int  MONTHS   = 36;
    private static final int  BASE_BPS = 500;

    // 보증 필수(minCount=1) 상품
    private Long gagrProdId;
    private Long gagrOkApplId;          // SIGNED 1명 → OK
    private Long gagrNoneApplId;        // 보증인 없음
    private Long gagrRegisteredApplId;  // REGISTERED 미서명만

    // 보증 필수(minCount=2) 상품 — SIGNED 1명이면 부족
    private Long gagr2ProdId;
    private Long gagr2InsufApplId;

    @BeforeAll
    void setup() throws Exception {
        gagrProdId = createGuarantorProduct(1);
        activateProduct(gagrProdId);

        gagrOkApplId         = createApplication(gagrProdId);
        gagrNoneApplId       = createApplication(gagrProdId);
        gagrRegisteredApplId = createApplication(gagrProdId);

        prepFullyEligible(gagrOkApplId);
        prepFullyEligible(gagrNoneApplId);
        prepFullyEligible(gagrRegisteredApplId);

        // 10번 — SIGNED 보증인 1명
        Long gagrId1 = registerGuarantor(gagrOkApplId, "01011110001");
        signGuarantor(gagrOkApplId, gagrId1);

        // 11번 — 보증인 없음 (별도 작업 없음)

        // 12번 — REGISTERED 만
        registerGuarantor(gagrRegisteredApplId, "01011110002");

        // 보증 필수(minCount=2) 상품
        gagr2ProdId    = createGuarantorProduct(2);
        activateProduct(gagr2ProdId);
        gagr2InsufApplId = createApplication(gagr2ProdId);
        prepFullyEligible(gagr2InsufApplId);
        Long gagrId3 = registerGuarantor(gagr2InsufApplId, "01011110003");
        signGuarantor(gagr2InsufApplId, gagrId3);
    }

    @Test @Order(10)
    void 보증필수_SIGNED_충족_APPROVED() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", gagrOkApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"));
    }

    @Test @Order(11)
    void 보증필수_보증인_없음_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", gagrNoneApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(12)
    void 보증필수_REGISTERED_미서명만_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", gagrRegisteredApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    @Test @Order(13)
    void 보증필수_SIGNED_수_부족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/review", gagr2InsufApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_038"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createGuarantorProduct(int minGuarantorCount) throws Exception {
        String code = "GGR_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"보증 필수 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false,
                  "guarantorRequiredYn":true,
                  "minGuarantorCount":%d
                }
                """.formatted(code, BASE_BPS, minGuarantorCount);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":9001, "prodId":%d, "channelCd":"MOBILE",
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

    private void prepFullyEligible(Long applId) throws Exception {
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
    }

    private Long registerGuarantor(Long applId, String mobileNo) throws Exception {
        String body = """
                {
                  "guarantorName":"보증인",
                  "guarantorMobileNo":"%s",
                  "relationTypeCd":"FAMILY",
                  "gagrTypeCd":"JOINT",
                  "guaranteeAmount":30000000
                }
                """.formatted(mobileNo);
        MvcResult result = mockMvc.perform(
                        post("/api/loan-applications/{applId}/guarantor-agreements", applId)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("gagrId").asLong();
    }

    private void signGuarantor(Long applId, Long gagrId) throws Exception {
        String body = """
                {
                  "signedDocUrl":"https://docs.bank.com/g%d.pdf",
                  "signedDocHash":"aabbcc%d"
                }
                """.formatted(gagrId, gagrId);
        mockMvc.perform(post("/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/sign",
                        applId, gagrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
