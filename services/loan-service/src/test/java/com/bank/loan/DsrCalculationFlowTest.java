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
 * DSR(한도) 산정 통합 테스트.
 *
 * 시나리오:
 *   10) PASS — 충분한 소득 / 낮은 부채 → ratio ≤ 4000, status=PASS, total = existing + new
 *   11) 동일 신청 재산정 차단 → 409 LOAN_036
 *   12) GET 단건 조회 OK
 *   13) FAIL — 과도한 부채 → ratio > limit, status=FAIL
 *   14) 신용평가 미수행 신청 → 422 LOAN_035
 *   15) GET 미존재 신청 → 404 LOAN_012
 *   16) GET DSR 안 한 신청 → 404 LOAN_037
 *   17) annualIncomeAmt 누락 → 400
 *   18) newRepay 미지정 → 서버 추정값 사용 (양수)
 *   19) dsrLimitBps 커스텀 적용 → 1000bps(10%) 면 FAIL
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DsrCalculationFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 30_000_000L;
    private static final int  MONTHS  = 36;
    private static final int  BASE_BPS = 500;

    private Long prodId;
    private Long passApplId;
    private Long failApplId;
    private Long noCevalApplId;
    private Long pristineCevalApplId;
    private Long autoEstimateApplId;
    private Long customLimitApplId;

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        passApplId = createApplication(prodId);
        failApplId = createApplication(prodId);
        noCevalApplId = createApplication(prodId);
        pristineCevalApplId = createApplication(prodId);
        autoEstimateApplId = createApplication(prodId);
        customLimitApplId = createApplication(prodId);

        // CB 통과 분만 DSR 가능
        runPrescAndCeval(passApplId);
        runPrescAndCeval(failApplId);
        runPrescAndCeval(pristineCevalApplId);
        runPrescAndCeval(autoEstimateApplId);
        runPrescAndCeval(customLimitApplId);
        // noCevalApplId 는 가심사만 통과, 신용평가 미수행
        runPrescreening(noCevalApplId, "PASS");
    }

    @Test @Order(10)
    void PASS_충분한_소득() throws Exception {
        // 연소득 8천만, 신규 연 원리금 1000만, 기존 0 → ratio ≈ 1250bps < 4000 → PASS
        String body = """
                {
                  "annualIncomeAmt":80000000,
                  "existingPrincipalTotal":0,
                  "existingAnnualRepayAmt":0,
                  "newAnnualRepayAmt":10000000
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", passApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applId").value(passApplId))
                .andExpect(jsonPath("$.data.dsrStatusCd").value("PASS"))
                .andExpect(jsonPath("$.data.dsrRatioBps").value(1250))
                .andExpect(jsonPath("$.data.totalAnnualRepayAmt").value(10000000))
                .andExpect(jsonPath("$.data.dsrLimitBps").value(4000))
                .andExpect(jsonPath("$.data.calculatedAt").exists());
    }

    @Test @Order(11)
    void 동일_신청_재산정_차단_409() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", passApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":1 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_036"));
    }

    @Test @Order(12)
    void GET_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/dsr-calculation", passApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applId").value(passApplId))
                .andExpect(jsonPath("$.data.dsrStatusCd").value("PASS"));
    }

    @Test @Order(13)
    void FAIL_과도한_부채() throws Exception {
        // 연소득 3천만, 기존 연 1500만 + 신규 1000만 → ratio ≈ 8333bps > 4000 → FAIL
        String body = """
                {
                  "annualIncomeAmt":30000000,
                  "existingPrincipalTotal":50000000,
                  "existingAnnualRepayAmt":15000000,
                  "newAnnualRepayAmt":10000000
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", failApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dsrStatusCd").value("FAIL"))
                .andExpect(jsonPath("$.data.totalAnnualRepayAmt").value(25000000));
    }

    @Test @Order(14)
    void 신용평가_미수행_사전조건_미충족_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", noCevalApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":1000000 }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_035"));
    }

    @Test @Order(15)
    void GET_미존재_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/dsr-calculation", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(16)
    void GET_DSR_안한_신청_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/dsr-calculation", pristineCevalApplId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_037"));
    }

    @Test @Order(17)
    void annualIncomeAmt_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", pristineCevalApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "newAnnualRepayAmt":1000000 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(18)
    void newRepay_미지정_서버추정() throws Exception {
        // 신청: 3천만, 36개월, baseRate 5% — 추정 연 원리금 ≈ 3천만/3 + 3천만×5% = 10000000+1500000 = 11500000
        String body = """
                {
                  "annualIncomeAmt":80000000
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", autoEstimateApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.newAnnualRepayAmt").value(11500000))
                .andExpect(jsonPath("$.data.dsrStatusCd").value("PASS"));
    }

    @Test @Order(19)
    void dsrLimitBps_커스텀_FAIL() throws Exception {
        // 한도 1000bps(10%) 로 강제 — ratio 1250 > 1000 → FAIL
        String body = """
                {
                  "annualIncomeAmt":80000000,
                  "newAnnualRepayAmt":10000000,
                  "dsrLimitBps":1000,
                  "dsrRegTypeCd":"STRICT"
                }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", customLimitApplId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dsrLimitBps").value(1000))
                .andExpect(jsonPath("$.data.dsrStatusCd").value("FAIL"))
                .andExpect(jsonPath("$.data.dsrRegTypeCd").value("STRICT"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "DSR_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"DSR 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":7001, "prodId":%d, "channelCd":"MOBILE",
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

    private void runPrescAndCeval(Long applId) throws Exception {
        runPrescreening(applId, "PASS");
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE", "cevalScore":700 }
                                """))
                .andExpect(status().isCreated());
    }
}
