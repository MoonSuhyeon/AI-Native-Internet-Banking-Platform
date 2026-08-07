package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.domain.CreditInfoReport;
import com.bank.loan.creditreport.repository.CreditInfoReportRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 01 step 7: 연체 라이프사이클 이벤트 → 신용정보 신고 자동 발화 통합 테스트.
 *
 * 메모리 룰: 배치성 테스트는 다른 연도 사용 — 본 테스트는 2033 년.
 *
 * 세팅 (cntrStartDate=20330101, autoDebit=N):
 *
 * 회차 납기는 휴일 보정(V8 + 영업일 캘린더 V9)을 거친다. 2033-02-01·03-01(삼일절)이
 * 휴일이라 실제 납기는 회차1=20330202, 회차2=20330302 로 하루씩 밀린다.
 * 롤오버는 dueDate < baseDate 인 회차를 찾으므로 baseDate 도 그만큼 뒤로 잡는다.
 * (보정 도입 전 기준인 20330201/20330301 을 가정하면 연체가 잡히지 않는다.)
 *
 *   10) baseDate=20330203 → 회차1 OVERDUE, dlq new ACTIVE, dlq_days=1, STAGE_0
 *                          → DELINQUENCY/OPENED 신고 1건
 *   11) baseDate=20330207 → dlq_days=5, STAGE_1 — 내부 단계 (신고 X). 자기 cntr 보고 그대로 1건
 *   12) baseDate=20330304 → 회차2도 OVERDUE, dlq_days=30, STAGE_2 — STAGE_ADVANCED 1건 추가 (총 2)
 *   13) baseDate=20330304 재실행 → 멱등 (총 2 그대로)
 *   14) 회차1·2 상환 후 baseDate=20330305 → RESOLVED → RESOLUTION 신고 1건 추가 (총 3)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportAutoEmitFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportRepository reportRepository;

    private static final String CNTR_START_DATE = "20330101";
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
        // 약정 체결 자동 신고(NEW_LOAN) 가 비동기로 적재될 때까지 기다린 뒤 이후 케이스의 카운트 베이스를 잡는다.
        awaitCntrReportCount(1);
    }

    @Test @Order(10)
    void opened_자동_신고() throws Exception {
        rollover("20330203");

        // NEW_LOAN(1) + DELINQUENCY_OPENED(1) = 2
        awaitCntrReportCount(2);

        CreditInfoReport opened = findLastByReason("DELINQUENCY_OPENED");
        assertThat(opened.getCrptTypeCd()).isEqualTo("DELINQUENCY");
        assertThat(opened.getCrptAgencyCd()).isEqualTo("KCB");
        // 자동 발화는 REQUESTED 까지다. SENT 로의 전이는 outbox 를 픽업하는
        // 별도 디스패치 배치(CreditInfoReportDispatchController)의 몫이며
        // 본 테스트 범위가 아니다.
        assertThat(opened.getCrptStatusCd()).isEqualTo("REQUESTED");
        assertThat(opened.getDlqId()).isNotNull();
        assertThat(opened.getReportPayload()).contains("dlqStartDate").contains("20330203");
    }

    @Test @Order(11)
    void stage_1_은_내부_단계_신고_없음() throws Exception {
        rollover("20330207");

        // 카운트 2 유지 — 잠시 기다리지만 새 row 생기면 안 됨
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(countCntrReports()).isEqualTo(2);
    }

    @Test @Order(12)
    void stage_2_진입은_STAGE_ADVANCED_자동_신고() throws Exception {
        rollover("20330304");

        // +1 → 3
        awaitCntrReportCount(3);
        CreditInfoReport adv = findLastByReason("DELINQUENCY_STAGE_ADVANCED");
        assertThat(adv.getCrptTypeCd()).isEqualTo("DELINQUENCY");
        // payload 는 콜론 뒤 공백을 포함해 직렬화된다("toStage": "STAGE_2").
        assertThat(adv.getReportPayload()).contains("\"toStage\": \"STAGE_2\"");
    }

    @Test @Order(13)
    void 같은_baseDate_재실행은_멱등() throws Exception {
        int before = countCntrReports();
        rollover("20330304");
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(countCntrReports()).isEqualTo(before);
    }

    @Test @Order(14)
    void 해소_자동_신고() throws Exception {
        repay(cntrId, 1);
        repay(cntrId, 2);
        rollover("20330305");

        awaitCntrReportCount(4);
        CreditInfoReport resolved = findLastByReason("DELINQUENCY_RESOLVED");
        assertThat(resolved.getCrptTypeCd()).isEqualTo("RESOLUTION");
        assertThat(resolved.getDlqId()).isNotNull();
    }

    // ============================================================
    // helpers
    // ============================================================

    private void rollover(String baseDate) throws Exception {
        mockMvc.perform(post("/api/internal/delinquency/rollover").param("baseDate", baseDate))
                .andExpect(status().isOk());
    }

    private void awaitCntrReportCount(int expected) {
        // 신고 적재는 @Async + AFTER_COMMIT 이라 rollover 응답 이후에 도착한다.
        // rollover 가 훑는 계약 수가 데모 시드(V37~V42)로 늘어 여유를 둔다.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countCntrReports() >= expected);
    }

    private int countCntrReports() {
        return reportRepository.findByCntrIdAndDeletedAtIsNullOrderByCreatedAtAsc(cntrId).size();
    }

    private CreditInfoReport findLastByReason(String reason) {
        List<CreditInfoReport> all = reportRepository.findByCntrIdAndDeletedAtIsNullOrderByCreatedAtAsc(cntrId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (reason.equals(all.get(i).getReportReasonCd())) return all.get(i);
        }
        throw new AssertionError("no report with reason=" + reason);
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "AUTOEMIT_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"자동발화 테스트", "loanTypeCd":"CREDIT",
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
                        .content("{ \"prodStatusCd\":\"ACTIVE\" }"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":5201, "prodId":%d, "channelCd":"MOBILE",
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
                        .header("Idempotency-Key", "ae-drawdown-" + UUID.randomUUID())
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
                        .header("Idempotency-Key", "ae-repay-" + installmentNo + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":%d, "channelCd":"MANUAL" }
                                """.formatted(installmentNo)))
                .andExpect(status().isCreated());
    }
}
