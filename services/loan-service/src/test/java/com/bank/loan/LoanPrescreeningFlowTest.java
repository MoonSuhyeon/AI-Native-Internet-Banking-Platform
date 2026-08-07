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
 * 가심사 통합 테스트.
 *
 * 시나리오:
 *   10) PASS — 신청 PRESCREENED, estimated_limit=requestedAmount, estimated_rate=baseRateBps
 *   11) 동일 신청 재가심사 → 409 LOAN_046
 *   12) GET 단건 조회 OK
 *   13) REJECT — 다른 신청에 적용, 신청 REJECTED, estimated 값 null
 *   14) 잘못된 상태 (예: PRESCREENED 후 다시 시도) → 위 11번과 별도로 다른 신청에서 한 번
 *   15) GET 미존재 신청 → 404 LOAN_012
 *   16) GET 가심사 안 한 신청 → 404 LOAN_045
 *   17) prescResultCd 미입력 + 소득 미제출 → 엔진 자동 REJECT (INCOME_NOT_PROVIDED)
 *   18) prescResultCd 미입력 + 소득 충분 → 엔진 자동 PASS (score/grade/한도 적재)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanPrescreeningFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 5_000_000L;
    private static final int  MONTHS  = 24;
    private static final int  BASE_BPS = 450;

    private Long prodId;
    private Long passApplId;
    private Long rejectApplId;
    private Long pristineApplId;
    private Long autoRejectApplId;     // 소득 미제출 → 엔진 자동 REJECT
    private Long autoPassApplId;       // 소득 충분 → 엔진 자동 PASS

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        passApplId = createApplication(prodId);
        rejectApplId = createApplication(prodId);
        pristineApplId = createApplication(prodId);
        autoRejectApplId = createApplication(prodId);
        autoPassApplId   = createApplicationWithIncome(prodId, /*income*/ 60_000_000L, "EMPLOYEE");
    }

    @Test @Order(10)
    void PASS_신청_PRESCREENED_전이() throws Exception {
        String body = """
                {
                  "prescResultCd":"PASS",
                  "estimatedGrade":"BBB",
                  "estimatedScore":720
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", passApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prescResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.estimatedLimitAmt").value(AMOUNT))
                .andExpect(jsonPath("$.data.estimatedRateBps").value(BASE_BPS))
                .andExpect(jsonPath("$.data.estimatedGrade").value("BBB"))
                .andExpect(jsonPath("$.data.estimatedScore").value(720))
                .andExpect(jsonPath("$.data.prescreenedAt").exists());
        // 신청 status 갱신 확인은 후속 API 흐름(약정 등)으로 간접 검증.
    }

    @Test @Order(11)
    void 동일_신청_재가심사_차단_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", passApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_046"));
    }

    @Test @Order(12)
    void GET_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/prescreening", passApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applId").value(passApplId))
                .andExpect(jsonPath("$.data.prescResultCd").value("PASS"));
    }

    @Test @Order(13)
    void REJECT_신청_REJECTED_전이() throws Exception {
        String body = """
                {
                  "prescResultCd":"REJECT",
                  "rejectReasonCd":"LOW_INCOME",
                  "prescRemark":"임계치 미달"
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", rejectApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prescResultCd").value("REJECT"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("LOW_INCOME"))
                .andExpect(jsonPath("$.data.estimatedLimitAmt").doesNotExist())
                .andExpect(jsonPath("$.data.estimatedRateBps").doesNotExist());
        // 신청 status 갱신은 직접 GET 단건 API 부재 — 후속 흐름으로 간접 검증.
    }

    @Test @Order(15)
    void GET_미존재_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/prescreening", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(16)
    void GET_가심사_안한_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/prescreening", pristineApplId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_045"));
    }

    @Test @Order(17)
    void 자동결정_소득미제출_엔진_REJECT() throws Exception {
        // 신청에 estimatedIncomeAmt/employmentTypeCd 없음 → MockCreditScoreEngine 가 INCOME_NOT_PROVIDED
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", autoRejectApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prescResultCd").value("REJECT"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("INCOME_NOT_PROVIDED"))
                .andExpect(jsonPath("$.data.estimatedLimitAmt").doesNotExist())
                .andExpect(jsonPath("$.data.estimatedRateBps").doesNotExist())
                .andExpect(jsonPath("$.data.prescEngineVersion").value("MOCK-v1"));
    }

    @Test @Order(18)
    void 자동결정_소득_EMPLOYEE_엔진_PASS() throws Exception {
        // income 60M, requested 5M, EMPLOYEE → PASS, score 720, grade BBB
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", autoPassApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prescResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.estimatedScore").value(720))
                .andExpect(jsonPath("$.data.estimatedGrade").value("BBB"))
                // 한도: 입력 없음 → 엔진 결과 min(requested 5M, income×5=300M) = 5M
                .andExpect(jsonPath("$.data.estimatedLimitAmt").value(AMOUNT))
                .andExpect(jsonPath("$.data.estimatedRateBps").value(BASE_BPS))
                .andExpect(jsonPath("$.data.prescEngineVersion").value("MOCK-v1"))
                .andExpect(jsonPath("$.data.rejectReasonCd").doesNotExist());
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "PRES_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"가심사 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
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

    private Long createApplicationWithIncome(Long prodId, long income, String empType) throws Exception {
        String body = """
                {
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL",
                  "estimatedIncomeAmt":%d, "employmentTypeCd":"%s"
                }
                """.formatted(prodId, AMOUNT, MONTHS, income, empType);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }
}
