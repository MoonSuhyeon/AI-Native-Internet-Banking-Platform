package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대출 약정(loan_contract) 생성·조회 통합 테스트.
 *
 * 흐름:
 *   1)  상품 등록 → ACTIVE → 신청 → 본심사 미구현이라 repository 로 APPROVED 강제
 *   10) 신청 미존재  → 404 LOAN_012
 *   11) 신청 SUBMITTED 상태로 약정 시도 → 422 LOAN_060
 *   12) contractedAmount > requestedAmount → 400 LOAN_061
 *   13) contractedPeriodMo > requestedPeriodMo → 400 LOAN_061
 *   20) APPROVED 신청으로 약정 생성 → 201 SIGNED, totalRate = base+spread-preferential, cntrEndDate 자동계산
 *   21) totalRateBps 명시값이 우선
 *   22) cntrStartDate / cntrEndDate 명시값이 우선
 *   30) 단건 조회 → 200
 *   31) 미존재 cntrId 조회 → 404 LOAN_062
 *   40) 약정 생성 시 만기 정보 자동 생성 (GET /maturity 로 확인)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanContractFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private Long activeProdId;
    private Long submittedApplId;   // APPROVED 미전이 — LOAN_060 검증용
    private Long approvedApplId;    // 정상 약정 진입
    private Long rangeApplId;       // LOAN_061 검증용 (requested 10_000_000 / 24mo)
    private Long explicitApplId;    // 명시 totalRate / dates 검증용
    private Long cntrId;

    @BeforeAll
    void setup() throws Exception {
        activeProdId = createProduct();
        activateProduct(activeProdId);

        submittedApplId = createApplication(activeProdId, 10_000_000L, 24);

        approvedApplId = createApplication(activeProdId, 10_000_000L, 24);
        forceApprove(approvedApplId);

        rangeApplId = createApplication(activeProdId, 10_000_000L, 24);
        forceApprove(rangeApplId);

        explicitApplId = createApplication(activeProdId, 10_000_000L, 24);
        forceApprove(explicitApplId);
    }

    // ============================================================
    // 검증 실패
    // ============================================================

    @Test @Order(10)
    void 신청_미존재_404() throws Exception {
        String body = """
                {
                  "applId":999999999,
                  "contractedAmount":1000000,
                  "contractedPeriodMo":12,
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """;
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(11)
    void 신청_APPROVED_아님_422() throws Exception {
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(submittedApplId, 5_000_000L, 12)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_060"));
    }

    @Test @Order(12)
    void 약정금액_초과_400() throws Exception {
        // rangeApplId requested = 10_000_000
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(rangeApplId, 20_000_000L, 24)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_061"));
    }

    @Test @Order(13)
    void 약정기간_초과_400() throws Exception {
        // rangeApplId requested period = 24mo
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(rangeApplId, 10_000_000L, 60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_061"));
    }

    // ============================================================
    // 약정 생성 — 자동 계산
    // ============================================================

    @Test @Order(20)
    void 약정_생성_자동계산() throws Exception {
        // baseRateBps=500, spreadBps=50, preferentialRateBps=30 → totalRate=520
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":8000000,
                  "contractedPeriodMo":24,
                  "baseRateBps":500,
                  "spreadBps":50,
                  "preferentialRateBps":30,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(approvedApplId);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cntrStatusCd").value("SIGNED"))
                .andExpect(jsonPath("$.data.cntrNo").exists())
                .andExpect(jsonPath("$.data.applId").value(approvedApplId))
                .andExpect(jsonPath("$.data.contractedAmount").value(8000000))
                .andExpect(jsonPath("$.data.contractedPeriodMo").value(24))
                .andExpect(jsonPath("$.data.baseRateBps").value(500))
                .andExpect(jsonPath("$.data.spreadBps").value(50))
                .andExpect(jsonPath("$.data.preferentialRateBps").value(30))
                .andExpect(jsonPath("$.data.totalRateBps").value(520))
                .andExpect(jsonPath("$.data.currencyCd").value("KRW"))
                .andExpect(jsonPath("$.data.cntrStartDate").exists())
                .andExpect(jsonPath("$.data.cntrEndDate").exists())
                .andExpect(jsonPath("$.data.signedAt").exists())
                .andReturn();
        JsonNode data = extractData(result);
        cntrId = data.get("cntrId").asLong();
        assertThat(cntrId).isPositive();

        // 신청 상태가 CONTRACTED 로 전이됐는지 확인
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(approvedApplId).orElseThrow();
        assertThat(app.currentStatus()).isEqualTo("CONTRACTED");
    }

    @Test @Order(21)
    void totalRateBps_명시값_우선() throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":5000000,
                  "contractedPeriodMo":12,
                  "baseRateBps":500,
                  "spreadBps":50,
                  "preferentialRateBps":30,
                  "totalRateBps":777,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(explicitApplId);
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalRateBps").value(777));
    }

    @Test @Order(22)
    void cntrStartDate_명시값_우선() throws Exception {
        // 위 21 에서 explicitApplId 가 CONTRACTED 됐을 것이므로 새 신청을 만들어 사용
        Long applId = createApplication(activeProdId, 10_000_000L, 24);
        forceApprove(applId);

        String body = """
                {
                  "applId":%d,
                  "contractedAmount":5000000,
                  "contractedPeriodMo":12,
                  "baseRateBps":400,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL",
                  "cntrStartDate":"20260601",
                  "cntrEndDate":"20270601"
                }
                """.formatted(applId);
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cntrStartDate").value("20260601"))
                .andExpect(jsonPath("$.data.cntrEndDate").value("20270601"));
    }

    // ============================================================
    // 조회
    // ============================================================

    @Test @Order(30)
    void 약정_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntrId").value(cntrId))
                .andExpect(jsonPath("$.data.cntrStatusCd").value("SIGNED"));
    }

    @Test @Order(31)
    void 약정_조회_미존재_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    // ============================================================
    // 부수효과 — 만기 정보 자동 생성
    // ============================================================

    @Test @Order(40)
    void 약정_생성시_만기정보_자동생성() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/maturity", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntrId").value(cntrId))
                .andExpect(jsonPath("$.data.originalMaturityDate").exists())
                .andExpect(jsonPath("$.data.currentMaturityDate").exists());
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "CNTR_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"약정 테스트 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(code);
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

    private Long createApplication(Long prodId, long amount, int periodMo) throws Exception {
        String body = """
                {
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, amount, periodMo);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    /**
     * 본심사 API 가 신청을 APPROVED 로 전이시키는 게 정상 흐름이지만, 본 테스트는
     * 약정 자체의 검증 규칙에만 집중하기 위해 repository 로 강제 전이.
     */
    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private String contractBody(Long applId, long amount, int periodMo) {
        return """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, amount, periodMo);
    }
}
