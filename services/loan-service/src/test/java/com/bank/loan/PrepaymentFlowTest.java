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
 * 중도상환(TYPE_EARLY) 통합 테스트.
 *
 * 연도 격리 — Contract A=20270101(미래), Contract B=20230101(과거).
 *
 * 흐름:
 *   세팅) Contract A (미래 cntrStart) — 정상/멱등/오류 케이스 공용
 *   10) 정상 prepay → tx 분배 (principal=amount, overdue=0, interest=0, fee>0), supersede + V2 생성
 *   11) 동일 idemKey 재호출 → 같은 rtxId 반환
 *   12) amount > outstanding → 422 LOAN_094
 *   13) amount=0 → 400 (validation)
 *   14) 미존재 cntrId → 404 LOAN_062
 *   20) Contract B (과거 cntrStart) + rollover → 회차1·2 OVERDUE → prepay
 *       → tx.overdueInterestAmount > 0, totalAmount = amount + overdue + fee, 항등식 성립
 *   30) 분배 항등식 — 두 계약의 모든 EARLY tx 에서 principal+interest+overdue+fee == total
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PrepaymentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    // 계약 시작일은 실제 오늘을 기준으로 잡는다.
    // 고정 일자를 박아두면 시간이 흐르면서 전제가 뒤집힌다 — 미래 계약은 언젠가 과거가 되어
    // 회차가 연체로 바뀌고, 과거 계약은 만기를 넘겨 잔여개월 0 → 중도상환 수수료 0 이 된다
    // (만기 후 상환은 '중도'가 아니므로 프로덕션이 맞다).
    private static final java.time.format.DateTimeFormatter YMD =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 계약 A — 연체 회차가 하나도 없어야 한다(분배에서 overdue=0 검증). 항상 미래 시작. */
    private static final String CNTR_START_A =
            java.time.LocalDate.now().plusMonths(6).withDayOfMonth(1).format(YMD);

    /** 계약 B — 연체 회차가 있으면서 잔여기간도 남아야 한다. 3개월 전 시작 → 회차1·2 연체, 잔여 9개월. */
    private static final String CNTR_START_B =
            java.time.LocalDate.now().minusMonths(3).withDayOfMonth(1).format(YMD);
    private static final String ROLLOVER_BASE_B = java.time.LocalDate.now().format(YMD);
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 600;

    private Long cntrIdA;
    private Long cntrIdB;
    private long rtxIdTest10;
    private final String idemKey10 = "pre-a1-" + UUID.randomUUID();

    @BeforeAll
    void setupContractA() throws Exception {
        Long prodId = createProduct("PRE_A_");
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrIdA = createContract(applId, CNTR_START_A);
        registerAndVerifyRepaymentAccount(cntrIdA);
        triggerDrawdown(cntrIdA, CONTRACTED_AMOUNT);
    }

    @Test @Order(10)
    void 정상_prepay_분배_supersede_V2() throws Exception {
        long amount = 500_000L;
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", cntrIdA)
                        .header("Idempotency-Key", idemKey10)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":%d, "channelCd":"MANUAL" }
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prepaidPrincipal").value(amount))
                .andExpect(jsonPath("$.data.outstandingAfter").value(CONTRACTED_AMOUNT - amount))
                .andExpect(jsonPath("$.data.newScheduleVersionCd").value("V2"))
                .andReturn();
        JsonNode data = extractData(result);
        rtxIdTest10 = data.get("rtxId").asLong();
        long fee   = data.get("feeAmount").asLong();
        long total = data.get("totalAmount").asLong();
        assertThat(fee).as("중도상환 수수료 양수").isPositive();
        assertThat(total).as("totalAmount = principal + fee (OVERDUE 없음)").isEqualTo(amount + fee);
        assertThat(data.get("supersededInstallments").asInt()).isPositive();

        // tx 의 overdue=0, interest=0 확인
        assertTxAllocation(cntrIdA, rtxIdTest10, total, amount, 0L, 0L, fee);

        // 스케줄: V2 의 DUE 회차들 존재 확인
        mockMvc.perform(get("/api/loan-contracts/{c}/repayment-schedules", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.rschVersionCd=='V2')]").exists());
    }

    @Test @Order(11)
    void 멱등성_동일키_재호출() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", cntrIdA)
                        .header("Idempotency-Key", idemKey10)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":500000, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(extractData(result).get("rtxId").asLong()).isEqualTo(rtxIdTest10);
    }

    @Test @Order(12)
    void 잔액_초과_422() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", cntrIdA)
                        .header("Idempotency-Key", "pre-over-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":99999999, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_094"));
    }

    @Test @Order(13)
    void amount_0_검증_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", cntrIdA)
                        .header("Idempotency-Key", "pre-zero-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":0 }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(14)
    void 미존재_계약_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", 999_999_999L)
                        .header("Idempotency-Key", "pre-no-cntr-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":100000 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(20)
    void OVERDUE_있을때_prepay_연체이자_추가수금() throws Exception {
        // Contract B (cntrStart=20230101) — 회차1·2 의 dueDate 가 과거.
        Long prodId = createProduct("PRE_B_");
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrIdB = createContract(applId, CNTR_START_B);
        registerAndVerifyRepaymentAccount(cntrIdB);
        triggerDrawdown(cntrIdB, CONTRACTED_AMOUNT);

        // 회차1·2 OVERDUE 전이
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", ROLLOVER_BASE_B))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/loan-contracts/{c}/repayment-schedules", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("OVERDUE"))
                .andExpect(jsonPath("$.data.items[1].rschStatusCd").value("OVERDUE"));

        // prepay 1,000,000 — 잔여 OVERDUE 회차의 연체이자가 추가로 수금돼야 한다.
        long amount = 1_000_000L;
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/prepayments", cntrIdB)
                        .header("Idempotency-Key", "pre-b-overdue-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount":%d, "channelCd":"MANUAL" }
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prepaidPrincipal").value(amount))
                .andReturn();
        JsonNode data = extractData(result);
        long rtxId = data.get("rtxId").asLong();
        long fee   = data.get("feeAmount").asLong();
        long total = data.get("totalAmount").asLong();
        assertThat(fee).isPositive();
        // total = amount + overdueInterest + fee → overdue = total - amount - fee
        long overdueImpliedByTotal = total - amount - fee;
        assertThat(overdueImpliedByTotal).as("총액에서 역산한 연체이자 양수").isPositive();

        // tx 의 직접 필드 확인
        JsonNode tx = findTxById(cntrIdB, rtxId);
        long txOverdue = tx.get("overdueInterestAmount").asLong();
        long txPrincipal = tx.get("principalAmount").asLong();
        long txInterest = tx.get("interestAmount").asLong();
        long txFee = tx.get("feeAmount").asLong();
        long txTotal = tx.get("totalAmount").asLong();
        assertThat(txPrincipal).isEqualTo(amount);
        assertThat(txInterest).isZero();
        assertThat(txOverdue).isEqualTo(overdueImpliedByTotal);
        assertThat(txPrincipal + txInterest + txOverdue + txFee).isEqualTo(txTotal);
    }

    @Test @Order(30)
    void 분배_항등식_모든_EARLY_tx() throws Exception {
        assertIdentityAllEarly(cntrIdA);
        assertIdentityAllEarly(cntrIdB);
    }

    // ============================================================
    // helpers
    // ============================================================

    private void assertIdentityAllEarly(Long cntrId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(result).get("items");
        int earlyCount = 0;
        for (JsonNode tx : items) {
            if (!"EARLY".equals(tx.get("rtxTypeCd").asText())) continue;
            earlyCount++;
            long total      = tx.get("totalAmount").asLong();
            long principal  = tx.get("principalAmount").asLong();
            long interest   = tx.get("interestAmount").asLong();
            long overdue    = tx.get("overdueInterestAmount").asLong();
            long fee        = tx.get("feeAmount").asLong();
            assertThat(principal + interest + overdue + fee)
                    .as("cntrId=%d rtxId=%s 분배 항등식", cntrId, tx.get("rtxId").asText())
                    .isEqualTo(total);
        }
        assertThat(earlyCount).as("EARLY tx 가 최소 1건 있어야 한다").isPositive();
    }

    private JsonNode findTxById(Long cntrId, long rtxId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(result).get("items");
        for (JsonNode tx : items) {
            if (tx.get("rtxId").asLong() == rtxId) return tx;
        }
        throw new IllegalStateException("rtxId=" + rtxId + " not found");
    }

    private void assertTxAllocation(Long cntrId, long rtxId,
                                    long total, long principal, long interest, long overdue, long fee) throws Exception {
        JsonNode tx = findTxById(cntrId, rtxId);
        assertThat(tx.get("totalAmount").asLong()).isEqualTo(total);
        assertThat(tx.get("principalAmount").asLong()).isEqualTo(principal);
        assertThat(tx.get("interestAmount").asLong()).isEqualTo(interest);
        assertThat(tx.get("overdueInterestAmount").asLong()).isEqualTo(overdue);
        assertThat(tx.get("feeAmount").asLong()).isEqualTo(fee);
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct(String prefix) throws Exception {
        String code = prefix + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"중도상환 테스트 상품", "loanTypeCd":"CREDIT",
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

    private Long createContract(Long applId, String cntrStartDate) throws Exception {
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
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, cntrStartDate);
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
                                  "holderName":"홍길동", "autoDebitYn":"N", "debitDay":1 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "pre-drawdown-" + UUID.randomUUID())
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
