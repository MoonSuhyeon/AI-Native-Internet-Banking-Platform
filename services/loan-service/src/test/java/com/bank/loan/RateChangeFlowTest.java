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
 * 금리 변경 + 스케줄 재생성 통합 테스트.
 *
 * 세팅 (cntrStartDate=20400101 — 다른 테스트와 격리):
 *   - 12회차 / EQUAL / 1200만 / 6% / 자동이체 N
 *   - 1·2회차 수동 상환 → PAID
 *
 * 시나리오:
 *   10) 변경 전 history 빈 리스트
 *   11) 6%→8% 변경, applied=20400301 → 3~12회차 SUPERSEDED + V2 신규 10건
 *   12) 계약 단건 조회로 totalRateBps=800 확인
 *   13) history 1건 (previous=600, new=800, reason=BASE_RATE_RESET)
 *   14) 8%→7% 다시 변경, applied=20400701 → V3 신규
 *   15) history 2건
 *   20) 모든 회차 PAID 후 변경 → 스케줄 재생성 안 함 (superseded/newCount=0)
 *   21) 잘못된 금리 (newTotalRateBps 음수) → 400 LOAN_110
 *   30) 미존재 계약 조회 → 404 LOAN_062
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateChangeFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20400101";
    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  INITIAL_RATE_BPS  = 600;

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
        repay(cntrId, 1);
        repay(cntrId, 2);
    }

    @Test @Order(10)
    void 변경전_history_빈리스트() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/rate-changes", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test @Order(11)
    void 첫_금리변경_V1_SUPERSEDED_V2_신규() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/rate-changes", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newBaseRateBps":800,
                                  "appliedStartDate":"20400301",
                                  "rateChangeReasonCd":"BASE_RATE_RESET"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.previousRateBps").value(INITIAL_RATE_BPS))
                .andExpect(jsonPath("$.data.newRateBps").value(800))
                .andExpect(jsonPath("$.data.newScheduleVersionCd").value("V2"))
                .andExpect(jsonPath("$.data.supersededInstallments").value(10))
                .andExpect(jsonPath("$.data.newInstallments").value(10));
    }

    @Test @Order(12)
    void 계약_totalRateBps_갱신_확인() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRateBps").value(800))
                .andExpect(jsonPath("$.data.baseRateBps").value(800));
    }

    @Test @Order(13)
    void history_1건() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/rate-changes", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].previousRateBps").value(INITIAL_RATE_BPS))
                .andExpect(jsonPath("$.data.items[0].newRateBps").value(800))
                .andExpect(jsonPath("$.data.items[0].rateChangeReasonCd").value("BASE_RATE_RESET"))
                .andExpect(jsonPath("$.data.items[0].appliedStartDate").value("20400301"));
    }

    @Test @Order(14)
    void 두번째_금리변경_V3() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/rate-changes", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newBaseRateBps":700,
                                  "appliedStartDate":"20400701",
                                  "rateChangeReasonCd":"PREF_CHANGE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.previousRateBps").value(800))
                .andExpect(jsonPath("$.data.newRateBps").value(700))
                .andExpect(jsonPath("$.data.newScheduleVersionCd").value("V3"));
    }

    @Test @Order(15)
    void history_2건() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/rate-changes", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].appliedStartDate").value("20400301"))
                .andExpect(jsonPath("$.data.items[1].appliedStartDate").value("20400701"));
    }

    @Test @Order(20)
    void 모든회차_PAID_상태_금리변경_스케줄_재생성_없음() throws Exception {
        // 본 테스트 cntrId 와 분리된 계약 생성 — 모든 회차 한꺼번에 상환
        Long isolatedCntrId = setupPaidContract();

        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/rate-changes", isolatedCntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newBaseRateBps":900,
                                  "appliedStartDate":"20400301",
                                  "rateChangeReasonCd":"DELINQ_PENALTY"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.supersededInstallments").value(0))
                .andExpect(jsonPath("$.data.newInstallments").value(0))
                .andReturn();

        long rchgId = extractData(result).get("rchgId").asLong();
        assert rchgId > 0;
    }

    @Test @Order(21)
    void 음수금리_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/rate-changes", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newBaseRateBps":0,
                                  "newSpreadBps":0,
                                  "newPreferentialRateBps":0,
                                  "newTotalRateBps":-1,
                                  "appliedStartDate":"20410101",
                                  "rateChangeReasonCd":"BASE_RATE_RESET"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(30)
    void 미존재_계약_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/rate-changes", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long setupPaidContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        Long c = createContract(applId);
        registerAndVerifyRepaymentAccount(c);
        triggerDrawdown(c, CONTRACTED_AMOUNT);
        for (int i = 1; i <= PERIOD_MONTHS; i++) {
            repay(c, i);
        }
        return c;
    }

    private Long createProduct() throws Exception {
        String code = "RCHG_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"금리변경 테스트", "loanTypeCd":"CREDIT",
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
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, INITIAL_RATE_BPS, CNTR_START_DATE);
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
                        .header("Idempotency-Key", "rchg-drawdown-" + UUID.randomUUID())
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
                        .header("Idempotency-Key", "rchg-repay-" + installmentNo + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":%d, "channelCd":"MANUAL" }
                                """.formatted(installmentNo)))
                .andExpect(status().isCreated());
    }
}
