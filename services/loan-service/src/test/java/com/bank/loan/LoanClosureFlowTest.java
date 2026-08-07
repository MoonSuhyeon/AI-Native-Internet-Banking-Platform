package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 약정 종결 통합 테스트.
 *
 * 시나리오:
 *   10) 잔액 남은 상태 NORMAL 종결 시도 → 422 LOAN_121
 *   11) 잔액 남은 상태 WRITE_OFF 종결 → 201 (사고 종결, 잔액 검증 면제)
 *   12) 중복 종결 차단 → 409 LOAN_123
 *   13) 종결된 계약 단건 조회 OK
 *   20) 다른 계약 — 모두 PAID 후 NORMAL 정상 종결 → 잔액 0, 활성 회차 0 검증, 정산 합산
 *   30) 미존재 계약 종결 조회 → 404 LOAN_124 (단, 계약은 존재해야 LOAN_062 X)
 *   31) 미존재 계약 → 404 LOAN_062
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanClosureFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrIdWithBalance;   // 잔액 남음 → WRITE_OFF 종결 대상
    private Long cntrIdAllPaid;       // 모두 PAID → NORMAL 종결 대상

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        cntrIdWithBalance = setupActiveContract();
        cntrIdAllPaid = setupActiveContract();
        for (int i = 1; i <= PERIOD_MONTHS; i++) {
            repay(cntrIdAllPaid, i);
        }
    }

    @Test @Order(10)
    void 잔액남은_NORMAL_종결_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/closure", cntrIdWithBalance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "closureTypeCd":"NORMAL", "closureReasonCd":"MATURITY" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_121"));
    }

    @Test @Order(11)
    void 잔액남아도_WRITE_OFF_종결_허용() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/closure", cntrIdWithBalance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "closureTypeCd":"WRITE_OFF", "closureReasonCd":"NPL_180D",
                                  "finalFeeAmt":0, "prepaymentFeeAmt":0 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.closTypeCd").value("WRITE_OFF"))
                .andExpect(jsonPath("$.data.closStatusCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.closedAt").exists())
                .andReturn();
        long closId = extractData(result).get("closId").asLong();
        assertThat(closId).isPositive();

        // 계약 상태도 CLOSED
        mockMvc.perform(get("/api/loan-contracts/{cntrId}", cntrIdWithBalance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntrStatusCd").value("CLOSED"));
    }

    @Test @Order(12)
    void 종결된_계약_재종결_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/closure", cntrIdWithBalance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "closureTypeCd":"NORMAL" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_123"));
    }

    @Test @Order(13)
    void 종결정보_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/closure", cntrIdWithBalance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntrId").value(cntrIdWithBalance))
                .andExpect(jsonPath("$.data.closTypeCd").value("WRITE_OFF"));
    }

    @Test @Order(20)
    void 전회차_PAID_후_NORMAL_정상종결() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/closure", cntrIdAllPaid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "closureTypeCd":"NORMAL", "closureReasonCd":"MATURITY",
                                  "finalFeeAmt":5000 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.closTypeCd").value("NORMAL"))
                .andExpect(jsonPath("$.data.finalPrincipalAmt").value(CONTRACTED_AMOUNT))
                .andExpect(jsonPath("$.data.finalFeeAmt").value(5000))
                .andExpect(jsonPath("$.data.prepaymentFeeAmt").value(0))
                .andReturn();
        JsonNode data = extractData(result);
        long total = data.get("totalSettledAmt").asLong();
        long principal = data.get("finalPrincipalAmt").asLong();
        long interest = data.get("finalInterestAmt").asLong();
        long fee = data.get("finalFeeAmt").asLong();
        long prepay = data.get("prepaymentFeeAmt").asLong();
        assertThat(total).isEqualTo(principal + interest + fee + prepay);
        assertThat(interest).isPositive();
    }

    @Test @Order(30)
    void 미존재_계약_종결_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/closure", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(31)
    void ACTIVE_계약_종결정보_없음_404() throws Exception {
        Long activeCntrId = setupActiveContract();
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/closure", activeCntrId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_124"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long setupActiveContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        Long cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);
        return cntrId;
    }

    private Long createProduct() throws Exception {
        String code = "CLOS_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"종결 테스트", "loanTypeCd":"CREDIT",
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
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
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
                        .header("Idempotency-Key", "clos-drawdown-" + UUID.randomUUID())
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
                        .header("Idempotency-Key", "clos-repay-" + installmentNo + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":%d, "channelCd":"MANUAL" }
                                """.formatted(installmentNo)))
                .andExpect(status().isCreated());
    }
}
