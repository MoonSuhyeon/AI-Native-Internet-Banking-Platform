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
 * 신용평가(CB) 통합 테스트.
 *
 * 시나리오:
 *   10) APPROVE — 가심사 PASS 완료 신청에 신용평가 적재, status=COMPLETED
 *   11) 동일 신청 재신용평가 → 409 LOAN_033
 *   12) GET 단건 조회 OK
 *   13) REVIEW — 다른 신청에 적용
 *   14) 가심사 미수행 신청에 신용평가 시도 → 422 LOAN_032
 *   15) 가심사 REJECT 된 신청에 신용평가 시도 → 422 LOAN_032
 *   16) GET 미존재 신청 → 404 LOAN_012
 *   17) GET 신용평가 안 한 신청 → 404 LOAN_034
 *   18) cevalDecisionCd 누락 → 400
 *   19) 잘못된 cevalDecisionCd → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditEvaluationFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 5_000_000L;
    private static final int  MONTHS  = 24;
    private static final int  BASE_BPS = 450;

    private Long prodId;
    private Long approveApplId;
    private Long reviewApplId;
    private Long noPrescApplId;
    private Long rejectedPrescApplId;
    private Long pristinePassApplId;

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        approveApplId = createApplication(prodId);
        reviewApplId = createApplication(prodId);
        noPrescApplId = createApplication(prodId);
        rejectedPrescApplId = createApplication(prodId);
        pristinePassApplId = createApplication(prodId);

        runPrescreening(approveApplId, "PASS");
        runPrescreening(reviewApplId, "PASS");
        runPrescreening(rejectedPrescApplId, "REJECT");
        runPrescreening(pristinePassApplId, "PASS");
    }

    @Test @Order(10)
    void APPROVE_신용평가_적재() throws Exception {
        String body = """
                {
                  "cevalEngine":"KCB",
                  "cevalEngineVersion":"v2026.05",
                  "cevalGrade":"BBB",
                  "cevalScore":712,
                  "pdBps":250,
                  "cevalDecisionCd":"APPROVE",
                  "evalLimitAmount":50000000,
                  "evalRateBps":480,
                  "cevalFactors":"{\\"income\\":42000000,\\"dti\\":0.31}"
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applId").value(approveApplId))
                .andExpect(jsonPath("$.data.cevalEngine").value("KCB"))
                .andExpect(jsonPath("$.data.cevalDecisionCd").value("APPROVE"))
                .andExpect(jsonPath("$.data.cevalScore").value(712))
                .andExpect(jsonPath("$.data.evalLimitAmount").value(50000000))
                .andExpect(jsonPath("$.data.cevalStatusCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.evaluatedAt").exists());
    }

    @Test @Order(11)
    void 동일_신청_재신용평가_차단_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", approveApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"NICE", "cevalDecisionCd":"APPROVE" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_033"));
    }

    @Test @Order(12)
    void GET_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/credit-evaluation", approveApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applId").value(approveApplId))
                .andExpect(jsonPath("$.data.cevalDecisionCd").value("APPROVE"));
    }

    @Test @Order(13)
    void REVIEW_신용평가_적재() throws Exception {
        String body = """
                {
                  "cevalEngine":"INTERNAL_XGB",
                  "cevalDecisionCd":"REVIEW",
                  "cevalScore":640
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", reviewApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cevalDecisionCd").value("REVIEW"))
                .andExpect(jsonPath("$.data.cevalEngine").value("INTERNAL_XGB"));
    }

    @Test @Order(14)
    void 가심사_미수행_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", noPrescApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_032"));
    }

    @Test @Order(15)
    void 가심사_REJECT_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", rejectedPrescApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_032"));
    }

    @Test @Order(16)
    void GET_미존재_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/credit-evaluation", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(17)
    void GET_신용평가_안한_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/credit-evaluation", pristinePassApplId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_034"));
    }

    @Test @Order(18)
    void cevalDecisionCd_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", pristinePassApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(19)
    void 잘못된_cevalDecisionCd_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", pristinePassApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"YES" }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "CEVL_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"신용평가 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":6001, "prodId":%d, "channelCd":"MOBILE",
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
}
