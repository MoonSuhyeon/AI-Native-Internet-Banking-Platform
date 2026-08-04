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
 * 자동이체 일배치 통합 테스트.
 *
 * 세팅 (cntrStartDate=20260101 고정 → 1회차 due_date=20260202):
 *
 * 2026-02-01 이 일요일이라 휴일 보정(V8 + 영업일 캘린더 V9)으로 납기가 20260202 로
 * 밀린다. 자동이체는 due_date = baseDate 로 정확히 매칭하므로 baseDate 도 맞춰야 한다.
 *   - 계약 A: auto_debit_yn=Y, racct VERIFIED → 처리 대상
 *   - 계약 B: auto_debit_yn=N, racct VERIFIED → skip
 *
 * 시나리오:
 *   10) 매칭 없는 baseDate → 0건
 *   11) 1차 실행 baseDate=20260202 → total=2, processed=1, skipped=1
 *   12) A 회차1 = PAID, B 회차1 = DUE 확인
 *   13) A 거래목록 1건 (channelCd=AUTO_DEBIT)
 *   14) 동일 baseDate 재실행 → total=1(B만 DUE 남음), processed=0, skipped=1
 *       A 거래목록 여전히 1건 (중복 출금 없음)
 *   15) baseDate 형식 오류 → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoDebitBatchTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20260101";
    private static final String DUE_DATE_OF_FIRST_INSTALLMENT = "20260202";
    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrIdA;
    private Long cntrIdB;

    @BeforeAll
    void setup() throws Exception {
        cntrIdA = setupContract("Y");
        cntrIdB = setupContract("N");
    }

    @Test @Order(10)
    void 매칭없는_baseDate_0건() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", "20991231"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCandidates").value(0))
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test @Order(11)
    void 일차_실행_A처리_B스킵() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", DUE_DATE_OF_FIRST_INSTALLMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseDate").value(DUE_DATE_OF_FIRST_INSTALLMENT))
                .andExpect(jsonPath("$.data.totalCandidates").value(2))
                .andExpect(jsonPath("$.data.processed").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test @Order(12)
    void A_회차1_PAID_B_회차1_DUE() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"));
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("DUE"));
    }

    @Test @Order(13)
    void A_거래목록_1건_AUTO_DEBIT() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].channelCd").value("INBOUND"))
                .andExpect(jsonPath("$.data.items[0].rtxStatusCd").value("SUCCESS"));
    }

    @Test @Order(14)
    void 동일_baseDate_재실행_중복출금없음() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", DUE_DATE_OF_FIRST_INSTALLMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCandidates").value(1))
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(1));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test @Order(15)
    void baseDate_형식오류_400() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", "2026-02-01"))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long setupContract(String autoDebitYn) throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        Long cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId, autoDebitYn);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);
        return cntrId;
    }

    private Long createProduct() throws Exception {
        String code = "AD_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"자동이체 테스트 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
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

    private void registerAndVerifyRepaymentAccount(Long cntrId, String autoDebitYn) throws Exception {
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901",
                  "holderName":"홍길동", "autoDebitYn":"%s", "debitDay":1 }
                """.formatted(autoDebitYn);
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "ad-drawdown-" + UUID.randomUUID())
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
}
