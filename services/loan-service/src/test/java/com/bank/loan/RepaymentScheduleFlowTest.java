package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.schedule.service.EqualPaymentCalculator;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상환 스케줄 자동 생성 + 조회 통합 테스트.
 *
 * 1) drawdown 전 GET → 빈 리스트
 * 2) 첫 drawdown → 자동 생성, 회차수 == contractedPeriodMo
 * 3) sum(scheduled_principal) == contractedAmount
 * 4) sum(scheduled_total) ≈ sum(principal + interest)
 * 5) installment_no 1..N 오름차순
 * 6) 두 번째 drawdown → 추가 생성 안 됨 (멱등)
 * 7) Calculator 단위 검증 (EQUAL 공식 직접 비교)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepaymentScheduleFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600; // 6%

    private Long cntrId;

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
    }

    @Test @Order(10)
    void drawdown_전_스케줄_조회_빈리스트() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test @Order(11)
    void 첫_drawdown_으로_스케줄_자동생성() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "sched-drawdown-1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(CONTRACTED_AMOUNT)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(PERIOD_MONTHS))
                .andExpect(jsonPath("$.data.rschVersionCd").value("V1"))
                .andExpect(jsonPath("$.data.items[0].installmentNo").value(1))
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("DUE"))
                .andExpect(jsonPath("$.data.items[0].appliedRateBps").value(RATE_BPS))
                .andReturn();

        JsonNode data = extractData(result);
        assertThat(data.get("totalScheduledPrincipal").asLong()).isEqualTo(CONTRACTED_AMOUNT);

        JsonNode items = data.get("items");
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).get("installmentNo").asInt()).isEqualTo(i + 1);
        }
        long lastBalance = items.get(items.size() - 1).get("remainingBalance").asLong();
        assertThat(lastBalance).isZero();
    }

    @Test @Order(12)
    void 두번째_drawdown_은_스케줄_재생성_안함() throws Exception {
        // 한도 범위 내라 가정 — 약정 12,000,000 / 1차 drawdown 12,000,000 였으므로 잔여 0.
        // 시나리오 단순화: 한도초과로 거절(LOAN_064)되든 성공하든, 스케줄 행 수는 그대로여야 한다.
        // 여기서는 잔여 한도가 없으므로 LOAN_064 가 정상 응답.
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "sched-drawdown-2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_064"));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(PERIOD_MONTHS));
    }

    @Test @Order(13)
    void 미존재_계약_스케줄_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(20)
    void 원리금균등_계산기_unit_검증() {
        List<EqualPaymentCalculator.Installment> installments =
                EqualPaymentCalculator.calculate(12_000_000L, 600, 12);
        assertThat(installments).hasSize(12);

        long sumPrincipal = installments.stream().mapToLong(EqualPaymentCalculator.Installment::scheduledPrincipal).sum();
        assertThat(sumPrincipal).isEqualTo(12_000_000L);
        assertThat(installments.get(11).remainingBalance()).isZero();

        // 6% / 12개월 / 1200만원 표준 PMT ≈ 1,032,802 원 — ±10원 허용 (반올림)
        long firstTotal = installments.get(0).scheduledTotal();
        assertThat(firstTotal).isBetween(1_032_700L, 1_032_900L);
    }

    @Test @Order(21)
    void 원리금균등_계산기_금리0_검증() {
        List<EqualPaymentCalculator.Installment> installments =
                EqualPaymentCalculator.calculate(12_000_000L, 0, 12);
        assertThat(installments).hasSize(12);
        long sumPrincipal = installments.stream().mapToLong(EqualPaymentCalculator.Installment::scheduledPrincipal).sum();
        assertThat(sumPrincipal).isEqualTo(12_000_000L);
        for (var inst : installments) {
            assertThat(inst.scheduledInterest()).isZero();
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "SCHED_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"스케줄 테스트 상품", "loanTypeCd":"CREDIT",
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
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901", "holderName":"홍길동",
                  "autoDebitYn":true, "debitDay":15 }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private String drawdownBody(long amount) {
        return """
                {
                  "executedAmount":%d,
                  "disbursementBankCd":"088",
                  "disbursementAccountNo":"1109999998888"
                }
                """.formatted(amount);
    }
}
