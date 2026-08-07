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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회차 부분상환(TYPE_PARTIAL) 통합 테스트.
 *
 * 연도 격리 — cntrStartDate=20240101 (과거 연도). 다른 테스트는 2026/2030/2040/2050 사용.
 * 과거 연도가 필요한 이유: PartialRepaymentService.computeOverdueInterest 가
 *   real wall-clock today 기준으로 days 를 계산하므로, OVERDUE 분배 케이스가 의미 있게
 *   동작하려면 dueDate < today 이어야 함.
 *
 * 흐름:
 *   세팅) 상품·신청·승인·약정·상환계좌(VERIFIED, autoDebit=N)·drawdown(스케줄 생성)
 *   10) DUE 회차1 부분상환 100,000 → PARTIAL_PAID, principal+interest=amount, overdue=0
 *   11) 회차1 잔액 만큼 두 번째 부분상환 → PAID 전이, cumulative=scheduled_total
 *   12) PAID 회차 부분상환 → 409 LOAN_091
 *   13) 잔액 초과 입력 → 422 LOAN_098
 *   14) 미존재 cntrId → 404 LOAN_062
 *   15) 미존재 회차 → 404 LOAN_090
 *   16) rollover(20240302) → 회차2 OVERDUE
 *   17) OVERDUE 회차2 부분상환 1,000 → 전액 overdue 로 분배(principal=interest=0)
 *   20) 분배 항등식: 모든 tx 에서 principal + interest + overdue + fee == total
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartialRepaymentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20240101";
    private static final String ROLLOVER_BASE_DATE = "20240302";
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 600;

    private Long cntrId;
    private long scheduledTotal1;
    private long rtxIdTest10;
    private long rtxIdTest11;
    private long rtxIdTest17;

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);
        scheduledTotal1 = fetchScheduledTotal(cntrId, 1);
    }

    @Test @Order(10)
    void DUE_회차_부분상환_원금이자_분배() throws Exception {
        long amount = 100_000L;
        MvcResult result = partialRepay(cntrId, 1, amount, "pr-due1-a-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scheduleStatusAfter").value("PARTIAL_PAID"))
                .andExpect(jsonPath("$.data.paidAmount").value(amount))
                .andExpect(jsonPath("$.data.cumulativePaid").value(amount))
                .andReturn();
        JsonNode data = extractData(result);
        long principal = data.get("principalPortion").asLong();
        long interest  = data.get("interestPortion").asLong();
        assertThat(principal + interest).isEqualTo(amount);
        assertThat(interest).isPositive();

        rtxIdTest10 = data.get("rtxId").asLong();
        // tx 의 overdue=0, fee=0 — DUE 회차이므로 연체이자 미발생
        assertTxAllocation(cntrId, rtxIdTest10, amount, principal, interest, 0L, 0L);
    }

    @Test @Order(11)
    void 두번째_부분상환으로_완납_PAID_전이() throws Exception {
        long remaining = scheduledTotal1 - 100_000L;
        MvcResult result = partialRepay(cntrId, 1, remaining, "pr-due1-b-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scheduleStatusAfter").value("PAID"))
                .andExpect(jsonPath("$.data.cumulativePaid").value(scheduledTotal1))
                .andReturn();
        rtxIdTest11 = extractData(result).get("rtxId").asLong();
    }

    @Test @Order(12)
    void PAID_회차_부분상환_거절() throws Exception {
        partialRepay(cntrId, 1, 1_000L, "pr-due1-paid-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_091"));
    }

    @Test @Order(13)
    void 잔액_초과_거절() throws Exception {
        partialRepay(cntrId, 2, 99_999_999L, "pr-due2-over-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_098"));
    }

    @Test @Order(14)
    void 미존재_계약_404() throws Exception {
        partialRepay(999_999_999L, 1, 1_000L, "pr-no-cntr-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(15)
    void 미존재_회차_404() throws Exception {
        partialRepay(cntrId, 999, 1_000L, "pr-no-inst-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_090"));
    }

    @Test @Order(16)
    void 회차2_OVERDUE_전이() throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", ROLLOVER_BASE_DATE))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"))   // 회차1 (이미 완납)
                .andExpect(jsonPath("$.data.items[1].rschStatusCd").value("OVERDUE")) // 회차2
                .andExpect(jsonPath("$.data.items[2].rschStatusCd").value("DUE"));    // 회차3 (dueDate > baseDate)
    }

    @Test @Order(17)
    void OVERDUE_회차_부분상환_연체이자_우선분배() throws Exception {
        // 회차2: dueDate=20240301, 오늘은 항상 dueDate 보다 한참 뒤이므로
        // overdueInterest >> 1,000원. amount=1,000 은 전부 overdue 에 귀속.
        long amount = 1_000L;
        MvcResult result = partialRepay(cntrId, 2, amount, "pr-overdue-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scheduleStatusAfter").value("PARTIAL_PAID"))
                .andExpect(jsonPath("$.data.paidAmount").value(amount))
                .andExpect(jsonPath("$.data.principalPortion").value(0))
                .andExpect(jsonPath("$.data.interestPortion").value(0))
                .andReturn();
        rtxIdTest17 = extractData(result).get("rtxId").asLong();
        // tx 의 overdueInterestAmount=amount, principal=interest=fee=0
        assertTxAllocation(cntrId, rtxIdTest17, amount, 0L, 0L, amount, 0L);
    }

    @Test @Order(20)
    void 분배_항등식_모든_tx() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(result).get("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(3); // 10/11/17
        for (JsonNode tx : items) {
            long total      = tx.get("totalAmount").asLong();
            long principal  = tx.get("principalAmount").asLong();
            long interest   = tx.get("interestAmount").asLong();
            long overdue    = tx.get("overdueInterestAmount").asLong();
            long fee        = tx.get("feeAmount").asLong();
            assertThat(principal + interest + overdue + fee)
                    .as("rtxId=%s 분배 항등식", tx.get("rtxId").asText())
                    .isEqualTo(total);
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private ResultActions partialRepay(
            Long cntrId, int installmentNo, long amount, String idemKey) throws Exception {
        return mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments/partial", cntrId)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "installmentNo":%d, "amount":%d, "channelCd":"MANUAL" }
                        """.formatted(installmentNo, amount)));
    }

    private long fetchScheduledTotal(Long cntrId, int installmentNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(result).get("items");
        for (JsonNode item : items) {
            if (item.get("installmentNo").asInt() == installmentNo) {
                return item.get("scheduledTotal").asLong();
            }
        }
        throw new IllegalStateException("installmentNo=" + installmentNo + " not found");
    }

    private void assertTxAllocation(Long cntrId, long rtxId,
                                    long total, long principal, long interest, long overdue, long fee) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(result).get("items");
        JsonNode tx = null;
        for (JsonNode item : items) {
            if (item.get("rtxId").asLong() == rtxId) { tx = item; break; }
        }
        assertThat(tx).as("rtxId=" + rtxId + " not found in list").isNotNull();
        assertThat(tx.get("totalAmount").asLong()).isEqualTo(total);
        assertThat(tx.get("principalAmount").asLong()).isEqualTo(principal);
        assertThat(tx.get("interestAmount").asLong()).isEqualTo(interest);
        assertThat(tx.get("overdueInterestAmount").asLong()).isEqualTo(overdue);
        assertThat(tx.get("feeAmount").asLong()).isEqualTo(fee);
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "PARTIAL_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"부분상환 테스트 상품", "loanTypeCd":"CREDIT",
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
                        .header("Idempotency-Key", "partial-drawdown-" + UUID.randomUUID())
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
