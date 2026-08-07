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
 * 일별 이자 발생 통합 테스트.
 *
 * 세팅 (cntrStartDate=20500101 — 다른 테스트와 격리):
 *   1200만원 / 6% / 12개월 / EQUAL / 자동이체 N
 *
 * 시나리오 (자기 cntrId 만 검증; 글로벌 카운트는 다른 테스트 영향):
 *   10) baseDate=20500101 1차 → 자기 계약 1건 생성 (≈ 1,973원)
 *   11) baseDate=20500102 2차 → 2건, cumulative ≈ 3,946
 *   12) 동일 baseDate=20500102 재호출 멱등 → 자기 계약 row 그대로 2건
 *   13) 회차1 상환 후 다음날 → 잔액 감소 → daily 감소 (≈ 1,813)
 *   14) 범위 조회 (from=20500101, to=20500102) → 2건
 *   15) 미존재 계약 조회 → 404 LOAN_062
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterestAccrualFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20500101";
    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrId;

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);
    }

    @Test @Order(10)
    void 첫_배치_일이자_생성() throws Exception {
        mockMvc.perform(post("/api/internal/interest-accrual/run").param("baseDate", "20500101"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].accrualDate").value("20500101"))
                .andExpect(jsonPath("$.data.items[0].principalBalance").value(CONTRACTED_AMOUNT))
                .andExpect(jsonPath("$.data.items[0].appliedRateBps").value(RATE_BPS))
                .andExpect(jsonPath("$.data.items[0].dayCountBasisCd").value("ACT/365"))
                .andExpect(jsonPath("$.data.items[0].iaccStatusCd").value("ACCRUED"))
                .andReturn();

        // 12,000,000 × 600bps / 10000 / 365 = 1,972.602... → HALF_EVEN 1,973
        long daily = extractData(result).get("items").get(0).get("dailyInterestAmt").asLong();
        long cumul = extractData(result).get("items").get(0).get("cumulativeInterestAmt").asLong();
        assertThat(daily).isEqualTo(1973L);
        assertThat(cumul).isEqualTo(daily);
    }

    @Test @Order(11)
    void 둘째날_누적_증가() throws Exception {
        mockMvc.perform(post("/api/internal/interest-accrual/run").param("baseDate", "20500102"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andReturn();
        JsonNode item2 = extractData(result).get("items").get(1);
        assertThat(item2.get("accrualDate").asText()).isEqualTo("20500102");
        assertThat(item2.get("dailyInterestAmt").asLong()).isEqualTo(1973L);
        assertThat(item2.get("cumulativeInterestAmt").asLong()).isEqualTo(3946L);
    }

    @Test @Order(12)
    void 동일_baseDate_재호출_멱등() throws Exception {
        mockMvc.perform(post("/api/internal/interest-accrual/run").param("baseDate", "20500102"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test @Order(13)
    void 회차상환후_잔액감소_일이자_감소() throws Exception {
        repay(cntrId, 1);

        mockMvc.perform(post("/api/internal/interest-accrual/run").param("baseDate", "20500201"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andReturn();
        JsonNode item3 = extractData(result).get("items").get(2);
        long principalAfterRepay = item3.get("principalBalance").asLong();
        long dailyAfterRepay = item3.get("dailyInterestAmt").asLong();
        assertThat(principalAfterRepay).isLessThan(CONTRACTED_AMOUNT);
        assertThat(dailyAfterRepay).isLessThan(1973L);
        // 잔액 ≈ 11,027,198 (회차1 principal ≈ 972,802 차감) → daily ≈ 1,813
        assertThat(dailyAfterRepay).isBetween(1_800L, 1_830L);
    }

    @Test @Order(14)
    void 범위_조회() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrId)
                        .param("from", "20500101").param("to", "20500102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test @Order(15)
    void 미존재_계약_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "IACC_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"이자발생 테스트", "loanTypeCd":"CREDIT",
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

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "bankCd":"088", "accountNo":"1102345678901",
                                  "holderName":"홍길동", "autoDebitYn":false, "debitDay":1 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "iacc-drawdown-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executedAmount":%d,
                                  "disbursementBankCd":"088",
                                  "disbursementAccountNo":"1109999998888"
                                }
                                """.formatted(amount)))
                .andExpect(status().isCreated());
    }

    private void repay(Long cntrId, int installmentNo) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", "iacc-repay-" + installmentNo + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":%d, "channelCd":"MANUAL" }
                                """.formatted(installmentNo)))
                .andExpect(status().isCreated());
    }
}
