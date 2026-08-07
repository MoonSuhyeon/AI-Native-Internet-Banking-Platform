package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 만기 관리 통합 테스트.
 *
 * 세팅 (cntrStartDate=20600101, 12개월 → cntr_end_date=20610101):
 *   20610101 은 토요일 → nextBusinessDay → 20610103(월요일)으로 보정.
 *   original_maturity_date = 계약서 원본 날짜 (20610101, 불변).
 *   current_maturity_date  = 영업일 보정 날짜 (20610103).
 *
 * 시나리오:
 *   10) 자동 생성 — original=20610101(토, 원본), current=20610103(월, 보정), count=0
 *   11) 6개월 연장 → 20610103+6mo=20610703(일) → 보정 20610704(월), count=1
 *   12) 12개월 연장 → 20610704+12mo=20620704(화, 보정 불요), count=2
 *   13) 음수 연장 → 400 (validation)
 *   14) 60개월 초과 → 400 (validation)
 *   15) 미존재 계약 조회 → 404 LOAN_062
 *   16) 연장 결과가 토요일인 경우 → 다음 영업일(월) 보정 확인
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MaturityFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20600101";
    /** 계약서 원본 만기일 (토요일) — original_maturity_date 는 보정 없이 이 값 그대로. */
    private static final String EXPECTED_ORIGINAL_MATURITY = "20610101";
    /** following 정책 적용 후 current_maturity_date (다음 월요일). */
    private static final String EXPECTED_CURRENT_MATURITY  = "20610103";
    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    @Test @Order(10)
    void 약정체결시_자동생성_만기일_토요일_보정() throws Exception {
        // 20610101(토) → original 은 원본 그대로, current 는 20610103(월)로 보정
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/maturity", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matStatusCd").value("ACTIVE"))
                .andExpect(jsonPath("$.data.originalMaturityDate").value(EXPECTED_ORIGINAL_MATURITY))
                .andExpect(jsonPath("$.data.currentMaturityDate").value(EXPECTED_CURRENT_MATURITY))
                .andExpect(jsonPath("$.data.extensionCount").value(0))
                .andExpect(jsonPath("$.data.lastExtendedDate").doesNotExist());
    }

    @Test @Order(11)
    void 육개월_연장() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/maturity/extend", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "extendedPeriodMo":6, "extensionTypeCd":"AGREEMENT" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalMaturityDate").value(EXPECTED_ORIGINAL_MATURITY))
                .andExpect(jsonPath("$.data.currentMaturityDate").value("20610704")) // 20610103+6mo=20610703(일) → 보정 20610704(월)
                .andExpect(jsonPath("$.data.extensionCount").value(1))
                .andExpect(jsonPath("$.data.extendedPeriodMo").value(6))
                .andExpect(jsonPath("$.data.extensionTypeCd").value("AGREEMENT"))
                .andExpect(jsonPath("$.data.lastExtendedDate").exists());
    }

    @Test @Order(12)
    void 다시_십이개월_연장_누적() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/maturity/extend", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "extendedPeriodMo":12 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMaturityDate").value("20620704")) // 20610704+12mo=20620704(화, 보정 불요)
                .andExpect(jsonPath("$.data.extensionCount").value(2))
                .andExpect(jsonPath("$.data.extendedPeriodMo").value(12))
                .andExpect(jsonPath("$.data.originalMaturityDate").value(EXPECTED_ORIGINAL_MATURITY));
    }

    @Test @Order(13)
    void 음수_연장_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/maturity/extend", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "extendedPeriodMo":-1 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(14)
    void 한도초과_연장_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/maturity/extend", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "extendedPeriodMo":61 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(15)
    void 미존재_계약_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/maturity", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(16)
    void 만기일_일요일_다음_영업일_보정() throws Exception {
        // 20600109(일요일 기준 cntrStart) → cntrEndDate=20610109(일요일)
        // → currentMaturityDate=20610110(월요일)
        Long sundayCntrId = setupContractWithStart("20600109");
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/maturity", sundayCntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalMaturityDate").value("20610109"))
                .andExpect(jsonPath("$.data.currentMaturityDate").value("20610110"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "MAT_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"만기 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
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

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    /** 별도 계약 start date 로 상품→신청→승인→약정 전 과정을 구성한다. */
    private Long setupContractWithStart(String startDate) throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
        return createContractWithStart(applId, startDate);
    }

    private Long createContractWithStart(Long applId, String startDate) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL",
                  "cntrStartDate":"%s"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, startDate);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }

    private Long createContract(Long applId) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL",
                  "cntrStartDate":"%s"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, CNTR_START_DATE);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
