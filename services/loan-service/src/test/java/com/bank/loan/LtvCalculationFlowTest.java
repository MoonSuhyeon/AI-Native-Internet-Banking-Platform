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
 * LTV(담보가치비율) 산정 통합 테스트.
 *
 * 시나리오:
 *   10) PASS — applied_value=200M, lien=0, requested=100M, limit=7000 → max=140M, ratio=5000 → PASS
 *   11) 동일 담보 재산정 차단 → 409 LOAN_053
 *   12) GET 단건 조회 OK
 *   13) FAIL — applied_value=100M, lien=50M, requested=80M, limit=7000 → max=20M, ratio=8000 → FAIL
 *   14) 감정평가 없는 담보 → 422 LOAN_052
 *   15) 해제된 담보 → 422 LOAN_052
 *   16) GET 미존재 담보 → 404 LOAN_050
 *   17) GET LTV 안 한 담보 → 404 LOAN_054
 *   18) 입력 override — appliedColValue·requestedAmount·ltvLimitBps 명시
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LtvCalculationFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 100_000_000L;
    private static final int  MONTHS  = 60;
    private static final int  BASE_BPS = 380;

    private Long prodId;
    private Long applId;
    private Long passColId;
    private Long failColId;
    private Long noEvalColId;
    private Long releasedColId;
    private Long pristineColId;
    private Long overrideColId;

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        applId = createApplication(prodId);

        passColId     = createCollateral(applId, /*lien*/ 0L);
        failColId     = createCollateral(applId, /*lien*/ 50_000_000L);
        noEvalColId   = createCollateral(applId, /*lien*/ 0L);
        releasedColId = createCollateral(applId, /*lien*/ 0L);
        pristineColId = createCollateral(applId, /*lien*/ 0L);
        overrideColId = createCollateral(applId, /*lien*/ 0L);

        evaluateCollateral(passColId,     200_000_000L);
        evaluateCollateral(failColId,     100_000_000L);
        evaluateCollateral(pristineColId, 200_000_000L);
        evaluateCollateral(overrideColId, 200_000_000L);
        // noEvalColId: 감정평가 없음
        // releasedColId: 평가는 했지만 해제 처리
        evaluateCollateral(releasedColId, 200_000_000L);
        releaseCollateral(releasedColId);
    }

    @Test @Order(10)
    void PASS_충분한_담보가치() throws Exception {
        // applied=200M, lien=0, requested=100M, limit=7000 → max=140M, ratio=5000 → PASS
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", passColId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.colId").value(passColId))
                .andExpect(jsonPath("$.data.applId").value(applId))
                .andExpect(jsonPath("$.data.appliedColValue").value(200_000_000))
                .andExpect(jsonPath("$.data.requestedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.data.ltvLimitBps").value(7000))
                .andExpect(jsonPath("$.data.maxLoanAmount").value(140_000_000))
                .andExpect(jsonPath("$.data.ltvRatioBps").value(5000))
                .andExpect(jsonPath("$.data.ltvStatusCd").value("PASS"))
                .andExpect(jsonPath("$.data.calculatedAt").exists());
    }

    @Test @Order(11)
    void 동일_담보_재산정_차단_409() throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", passColId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_053"));
    }

    @Test @Order(12)
    void GET_단건_조회() throws Exception {
        mockMvc.perform(get("/api/collaterals/{colId}/ltv-calculation", passColId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.colId").value(passColId))
                .andExpect(jsonPath("$.data.ltvStatusCd").value("PASS"));
    }

    @Test @Order(13)
    void FAIL_선순위_채권으로_한도부족() throws Exception {
        // applied=100M, lien=50M, requested=100M, limit=7000 → ceiling=70M, max=70M-50M=20M, ratio=10000 → FAIL
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", failColId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.appliedColValue").value(100_000_000))
                .andExpect(jsonPath("$.data.seniorLienAmount").value(50_000_000))
                .andExpect(jsonPath("$.data.maxLoanAmount").value(20_000_000))
                .andExpect(jsonPath("$.data.ltvStatusCd").value("FAIL"));
    }

    @Test @Order(14)
    void 감정평가_없는_담보_422() throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", noEvalColId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_052"));
    }

    @Test @Order(15)
    void 해제된_담보_422() throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", releasedColId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_052"));
    }

    @Test @Order(16)
    void GET_미존재_담보_404() throws Exception {
        mockMvc.perform(get("/api/collaterals/{colId}/ltv-calculation", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_050"));
    }

    @Test @Order(17)
    void GET_LTV_안한_담보_404() throws Exception {
        mockMvc.perform(get("/api/collaterals/{colId}/ltv-calculation", pristineColId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_054"));
    }

    @Test @Order(18)
    void 입력_override() throws Exception {
        // 명시 입력: applied=150M, lien=10M, requested=70M, limit=6000(60%)
        // ceiling=90M, max=80M, ratio=70M/150M*10000≈4667 → PASS
        String body = """
                {
                  "appliedColValue":150000000,
                  "seniorLienAmount":10000000,
                  "requestedAmount":70000000,
                  "ltvLimitBps":6000,
                  "calcEngineVersion":"v2026.05"
                }
                """;
        mockMvc.perform(post("/api/collaterals/{colId}/ltv-calculation", overrideColId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.appliedColValue").value(150_000_000))
                .andExpect(jsonPath("$.data.seniorLienAmount").value(10_000_000))
                .andExpect(jsonPath("$.data.requestedAmount").value(70_000_000))
                .andExpect(jsonPath("$.data.ltvLimitBps").value(6000))
                .andExpect(jsonPath("$.data.maxLoanAmount").value(80_000_000))
                .andExpect(jsonPath("$.data.ltvRatioBps").value(4667))
                .andExpect(jsonPath("$.data.ltvStatusCd").value("PASS"))
                .andExpect(jsonPath("$.data.calcEngineVersion").value("v2026.05"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "LTV_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"LTV 테스트", "loanTypeCd":"MORTGAGE",
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

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":9001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"HOUSING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, AMOUNT, MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private Long createCollateral(Long applId, Long seniorLien) throws Exception {
        String body = """
                {
                  "colTypeCd":"REAL_ESTATE",
                  "colName":"테스트 부동산",
                  "colAddress":"서울특별시 강남구",
                  "declaredValue":200000000,
                  "currencyCd":"KRW",
                  "ownershipTypeCd":"SOLE",
                  "seniorLienYn":"%s",
                  "seniorLienAmount":%d
                }
                """.formatted(seniorLien > 0, seniorLien);
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("colId").asLong();
    }

    private void evaluateCollateral(Long colId, Long appliedValue) throws Exception {
        String body = """
                {
                  "evalMethodCd":"APPRAISAL",
                  "evalAgencyCd":"KAB",
                  "appraisedValue":%d,
                  "appliedValue":%d,
                  "appliedStartDate":"20260101",
                  "appliedEndDate":"20271231"
                }
                """.formatted(appliedValue, appliedValue);
        mockMvc.perform(post("/api/collaterals/{colId}/evaluations", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void releaseCollateral(Long colId) throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/release", colId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "releaseReasonCd":"TEST", "releaseRemark":"테스트용 해제" }
                                """))
                .andExpect(status().isOk());
    }
}
