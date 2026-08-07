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
 * 회차 수동 상환 통합 테스트.
 *
 * 흐름:
 *   세팅) 상품·신청·승인·약정·상환계좌(VERIFIED)·drawdown(스케줄 자동생성)
 *   10) 거래 목록 빈 리스트
 *   11) 1회차 상환 → 201, 분배·잔여 검증
 *   12) 동일 Idempotency-Key 재호출 → 동일 tx 반환 (멱등)
 *   13) 1회차 재상환 차단 → 409 LOAN_091
 *   14) 미존재 회차 상환 → 404 LOAN_090
 *   15) 2회차 상환 (다른 키)
 *   16) 거래 목록 2건
 *   17) 스케줄 조회로 1·2회차 PAID 확인
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepaymentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrId;
    private String idemKey1;
    private long rtxIdFromFirstCall;

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);
        idemKey1 = "rp-1-" + UUID.randomUUID();
    }

    @Test @Order(10)
    void 거래_목록_빈리스트() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.totalPaidAmount").value(0));
    }

    @Test @Order(11)
    void 일회차_상환() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", idemKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rtxStatusCd").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rtxTypeCd").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.channelCd").value("MANUAL"))
                .andExpect(jsonPath("$.data.overdueInterestAmount").value(0))
                .andExpect(jsonPath("$.data.feeAmount").value(0))
                .andExpect(jsonPath("$.data.paidAt").exists())
                .andReturn();

        JsonNode data = extractData(result);
        rtxIdFromFirstCall = data.get("rtxId").asLong();
        long total = data.get("totalAmount").asLong();
        long principal = data.get("principalAmount").asLong();
        long interest = data.get("interestAmount").asLong();
        assertThat(total).isEqualTo(principal + interest);
        // 1200만원/6%/12개월 EQUAL → 1회차 합계 ≈ 1,032,802 (±몇 십원)
        assertThat(total).isBetween(1_032_000L, 1_034_000L);
    }

    @Test @Order(12)
    void 멱등성_동일키_재호출() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", idemKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long rtxId = extractData(result).get("rtxId").asLong();
        assertThat(rtxId).isEqualTo(rtxIdFromFirstCall);
    }

    @Test @Order(13)
    void 이미_상환된_회차_재상환_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", "rp-1-dup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_091"));
    }

    @Test @Order(14)
    void 미존재_회차_상환_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", "rp-999-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":999, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_090"));
    }

    @Test @Order(15)
    void 이회차_상환() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                        .header("Idempotency-Key", "rp-2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":2 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rtxStatusCd").value("SUCCESS"))
                .andExpect(jsonPath("$.data.channelCd").value("MANUAL"));
    }

    @Test @Order(16)
    void 거래_목록_2건() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].rschId").exists())
                .andExpect(jsonPath("$.data.items[1].rschId").exists());
    }

    @Test @Order(17)
    void 스케줄_조회로_PAID_확인() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"))
                .andExpect(jsonPath("$.data.items[1].rschStatusCd").value("PAID"))
                .andExpect(jsonPath("$.data.items[2].rschStatusCd").value("DUE"));
    }

    @Test @Order(18)
    void 미존재_계약_거래_목록_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayments", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    /**
     * OVERDUE 회차의 정확액 상환 → 연체이자 분배 회귀.
     * 연도 격리 — 새 계약은 cntrStartDate=20220101 (과거, 미사용 연도) 로 별도 생성.
     * 메인 계약(@BeforeAll, 오늘) 은 영향 없음.
     */
    @Test @Order(20)
    void OVERDUE_회차_상환_연체이자_분배() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        Long overdueCntrId = createContract(applId, "20220101");
        registerAndVerifyRepaymentAccount(overdueCntrId);
        triggerDrawdown(overdueCntrId, CONTRACTED_AMOUNT);

        long scheduledTotal1 = fetchScheduledTotal(overdueCntrId, 1);

        // 회차1 OVERDUE 로 전이
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20220302"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", overdueCntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("OVERDUE"));

        // 회차1 정확액 상환 — OVERDUE 분배 활성화
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", overdueCntrId)
                        .header("Idempotency-Key", "rp-overdue-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rtxStatusCd").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rtxTypeCd").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.feeAmount").value(0))
                .andReturn();
        JsonNode data = extractData(result);
        long total     = data.get("totalAmount").asLong();
        long principal = data.get("principalAmount").asLong();
        long interest  = data.get("interestAmount").asLong();
        long overdue   = data.get("overdueInterestAmount").asLong();
        long fee       = data.get("feeAmount").asLong();

        assertThat(overdue).as("OVERDUE 회차 연체이자 양수").isPositive();
        assertThat(total).as("totalAmount = scheduledTotal + overdue").isEqualTo(scheduledTotal1 + overdue);
        assertThat(principal + interest + overdue + fee)
                .as("분배 항등식")
                .isEqualTo(total);
        // 회차1 PAID 전이 확인
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", overdueCntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"));
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

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "REPAY_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"상환 테스트 상품", "loanTypeCd":"CREDIT",
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
        return createContract(applId, null);
    }

    private Long createContract(Long applId, String cntrStartDate) throws Exception {
        String startDateField = cntrStartDate == null
                ? ""
                : ",\"cntrStartDate\":\"" + cntrStartDate + "\"";
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                  %s
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, startDateField);
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
                                  "holderName":"홍길동", "autoDebitYn":true, "debitDay":15 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "rp-init-drawdown-" + UUID.randomUUID())
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
