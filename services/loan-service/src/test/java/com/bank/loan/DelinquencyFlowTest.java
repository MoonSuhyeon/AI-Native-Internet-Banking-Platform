package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.delinquency.domain.Delinquency;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연체 라이프사이클 통합 테스트.
 *
 * 세팅 (cntrStartDate=20300101 → 회차1 due=20300201, 회차2 due=20300301):
 *   - 자동이체 미설정 (auto_debit_yn=N) — DUE 가 자동 PAID 안 됨
 *
 * 시나리오:
 *   10) baseDate=20300201 → due_date 와 같음 → 0건 (< 비교)
 *   11) baseDate=20300202 → 회차1 OVERDUE, dlq 신규 ACTIVE, dlq_days=1, STAGE_0
 *   12) 동일 baseDate 재실행 → 멱등 (스냅샷 중복 없음, 회차 그대로)
 *   13) baseDate=20300206 → dlq_days=5, STAGE_1, 스냅샷 2건
 *   14) baseDate=20300305 → 회차2도 OVERDUE, principal/interest 누적
 *       (회차2 납기 20300301 은 삼일절이라 20300304 로 보정됨)
 *   15) 활성 dlq 조회 OK
 *   16) 스냅샷 목록 조회 (3건)
 *   17) 회차1·2 수동 상환 후 다음 rollover → RESOLVED
 *   18) 해소된 계약 활성 dlq 조회 → 404 LOAN_100
 *   20) Delinquency.stageOf unit
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DelinquencyFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20300101";
    private static final String DUE_1 = "20300201";
    private static final String DUE_2 = "20300304";  // 20300301 삼일절 → 휴일 보정
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
    void baseDate_가_due와_같으면_자기계약_overdue_없음() throws Exception {
        // 글로벌 카운트는 다른 테스트의 회차에 영향받으므로 자기 cntrId 만 검증
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", DUE_1))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("DUE"));
    }

    @Test @Order(11)
    void 회차1_overdue_dlq_신규생성() throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20300202"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dlqStatusCd").value("ACTIVE"))
                .andExpect(jsonPath("$.data.dlqDays").value(1))
                .andExpect(jsonPath("$.data.dlqStageCd").value("STAGE_0"))
                .andExpect(jsonPath("$.data.dlqStartDate").value("20300202"));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("OVERDUE"));
    }

    @Test @Order(12)
    void 동일_baseDate_재실행_멱등() throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20300202"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency/snapshots", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test @Order(13)
    void 오일후_stage1_전환() throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20300206"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dlqDays").value(5))
                .andExpect(jsonPath("$.data.dlqStageCd").value("STAGE_1"));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency/snapshots", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test @Order(14)
    void 회차2_overdue_누적() throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20300305"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        long total = extractData(result).get("dlqTotalAmt").asLong();
        long principal = extractData(result).get("dlqPrincipalAmt").asLong();
        long interest = extractData(result).get("dlqInterestAmt").asLong();
        assertThat(total).isEqualTo(principal + interest);
        // 회차1+회차2 합계 — EQUAL 원리금균등 1200만/6%/12m 기준 약 2,065,xxx
        assertThat(total).isBetween(2_064_000L, 2_066_000L);
    }

    @Test @Order(15)
    void 스냅샷_3건_누적() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency/snapshots", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.items[0].snapshotDate").value("20300202"))
                .andExpect(jsonPath("$.data.items[1].snapshotDate").value("20300206"))
                .andExpect(jsonPath("$.data.items[2].snapshotDate").value("20300305"));
    }

    @Test @Order(16)
    void 회차_상환후_해소() throws Exception {
        repay(cntrId, 1);
        repay(cntrId, 2);

        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", "20300306"))
                .andExpect(status().isOk());
        // 자기 dlq 가 RESOLVED 됐는지는 다음 테스트의 LOAN_100 응답으로 검증
    }

    @Test @Order(17)
    void 해소후_활성_dlq_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_100"));
    }

    @Test @Order(18)
    void 미존재_계약_조회_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(20)
    void stageOf_unit() {
        assertThat(Delinquency.stageOf(0)).isEqualTo("STAGE_0");
        assertThat(Delinquency.stageOf(4)).isEqualTo("STAGE_0");
        assertThat(Delinquency.stageOf(5)).isEqualTo("STAGE_1");
        assertThat(Delinquency.stageOf(29)).isEqualTo("STAGE_1");
        assertThat(Delinquency.stageOf(30)).isEqualTo("STAGE_2");
        assertThat(Delinquency.stageOf(89)).isEqualTo("STAGE_2");
        assertThat(Delinquency.stageOf(90)).isEqualTo("STAGE_3");
        assertThat(Delinquency.stageOf(365)).isEqualTo("STAGE_3");
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "DLQ_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"연체 테스트 상품", "loanTypeCd":"CREDIT",
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
                        .header("Idempotency-Key", "dlq-drawdown-" + UUID.randomUUID())
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
                        .header("Idempotency-Key", "dlq-repay-" + installmentNo + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":%d, "channelCd":"MANUAL" }
                                """.formatted(installmentNo)))
                .andExpect(status().isCreated());
    }
}
