package com.bank.loan;

import com.bank.loan.accounting.repository.DailyAccountingSummaryRepository;
import com.bank.loan.accounting.repository.MonthlyAccountingSummaryRepository;
import com.bank.loan.ecl.repository.LoanEclSummaryRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.delinquency.repository.OverdueAccrualRepository;
import com.bank.loan.notification.outbox.NotificationOutboxRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.springframework.data.domain.PageRequest;
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
 * LoanEodJob 통합 테스트 (연도: 2035 — 다른 배치 테스트와 날짜 충돌 방지).
 *
 * 세팅 (cntrStartDate=20350101 → 회차1 due_date=20350201):
 *   - 계약 A: auto_debit_yn=Y, VERIFIED → 자동이체 처리 대상
 *   - 계약 B: auto_debit_yn=N          → 자동이체 skip, 미납 시 연체 전환 대상
 *
 * 시나리오:
 *   10) EOD baseDate=20350201 (납기일) → COMPLETED
 *   11) A 회차1=PAID, B 회차1=DUE 확인
 *   12) A·B 이자발생 행 존재 확인
 *   13) 연체 아직 없음 (납기일 당일은 OVERDUE 아님)
 *   20) EOD baseDate=20350205 (납기일+4일) → COMPLETED
 *   21) B 회차1=OVERDUE
 *   22) B 연체 ACTIVE, dlqDays=4, STAGE_0
 *   23) A 연체 없음 (납기일에 이미 PAID)
 *   24) B 연체 이자 발생행 존재, dailyOverdueInterest > 0
 *   25) A 연체 이자 발생행 없음
 *   30) 동일 baseDate=20350201 재실행 → SKIPPED (JobInstanceAlreadyComplete)
 *   40) baseDate 형식 오류 → 400
 *   50) EOD 이력 조회: baseDate 필터 → COMPLETED 1건, 스텝 9개
 *   51) from/to 매칭 없으면 빈 배열
 *   60) restart 미존재 baseDate → NOT_FOUND
 *   61) restart COMPLETED 잡 → REJECTED
 *   70) 잡 종료 알림 outbox 적재 — COMPLETED 잡마다 LOAN_EOD_COMPLETED 1건
 *   80) notificationFlushStep — outbox 상당수가 SENT 로 전이됨
 *   90) 일일 회계 요약 적재 — 20350201 자동이체·이자, 20350205 연체이자
 *   100) EOM run baseMonth=203502 → COMPLETED + 합계 적재
 *   101) EOM 동일 baseMonth 재실행 → SKIPPED
 *   102) EOM baseMonth 형식 오류 → 400
 *   103) EOM 후 ECL 산출 — A(Stage 1) + B(Stage 2, 연체 STAGE_0)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanEodJobTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;
    @Autowired
    private OverdueAccrualRepository overdueAccrualRepository;
    @Autowired
    private NotificationOutboxRepository outboxRepository;
    @Autowired
    private DailyAccountingSummaryRepository accountingSummaryRepository;
    @Autowired
    private MonthlyAccountingSummaryRepository monthlySummaryRepository;
    @Autowired
    private LoanEclSummaryRepository eclRepository;

    private static final String CNTR_START_DATE = "20350101";
    private static final String EOD_DUE_DATE    = "20350201";  // 납기일 당일
    private static final String EOD_OVERDUE     = "20350205";  // 납기일 +4일 → dlqDays=4, STAGE_0

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

    // ────────────────────────────────────────────
    // Phase 1: EOD on due date
    // ────────────────────────────────────────────

    @Test @Order(10)
    void EOD_납기일_실행_COMPLETED() throws Exception {
        mockMvc.perform(post("/api/internal/eod/run").param("baseDate", EOD_DUE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.baseDate").value(EOD_DUE_DATE));
    }

    @Test @Order(11)
    void A_회차1_PAID_B_회차1_DUE() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("DUE"));
    }

    @Test @Order(12)
    void A_B_이자발생_행_존재() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrIdA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].accrualDate").value(EOD_DUE_DATE));

        mockMvc.perform(get("/api/loan-contracts/{cntrId}/interest-accruals", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].accrualDate").value(EOD_DUE_DATE));
    }

    @Test @Order(13)
    void 납기일_당일_연체_없음() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrIdB))
                .andExpect(status().isNotFound());
    }

    // ────────────────────────────────────────────
    // Phase 2: EOD 4 days past due → delinquency
    // ────────────────────────────────────────────

    @Test @Order(20)
    void EOD_연체일_실행_COMPLETED() throws Exception {
        mockMvc.perform(post("/api/internal/eod/run").param("baseDate", EOD_OVERDUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.baseDate").value(EOD_OVERDUE));
    }

    @Test @Order(21)
    void B_회차1_OVERDUE() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-schedules", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("OVERDUE"));
    }

    @Test @Order(22)
    void B_연체_ACTIVE_dlqDays_4_STAGE_0() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dlqStatusCd").value("ACTIVE"))
                .andExpect(jsonPath("$.data.dlqDays").value(4))
                .andExpect(jsonPath("$.data.dlqStageCd").value("STAGE_0"));
    }

    @Test @Order(23)
    void A_연체_없음_납기일에_이미_상환() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrIdA))
                .andExpect(status().isNotFound());
    }

    @Test @Order(24)
    void B_연체이자_발생행_존재_dailyOverdueInterest_양수() {
        var rows = overdueAccrualRepository.findAll().stream()
                .filter(oa -> oa.getCntrId().equals(cntrIdB))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAccrualDate()).isEqualTo(EOD_OVERDUE);
        assertThat(rows.get(0).getDailyOverdueInterest()).isGreaterThan(0L);
        assertThat(rows.get(0).getCumulativeOverdueInterest()).isGreaterThan(0L);
    }

    @Test @Order(25)
    void A_연체이자_발생행_없음() {
        boolean exists = overdueAccrualRepository.existsByCntrIdAndAccrualDate(cntrIdA, EOD_OVERDUE);
        assertThat(exists).isFalse();
    }

    // ────────────────────────────────────────────
    // Phase 3: 멱등성 + 유효성 검사
    // ────────────────────────────────────────────

    @Test @Order(30)
    void 동일_baseDate_재실행_SKIPPED() throws Exception {
        mockMvc.perform(post("/api/internal/eod/run").param("baseDate", EOD_DUE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("SKIPPED"));
    }

    @Test @Order(40)
    void baseDate_형식오류_400() throws Exception {
        mockMvc.perform(post("/api/internal/eod/run").param("baseDate", "2035-02-01"))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────
    // Phase 4: 이력 조회
    // ────────────────────────────────────────────

    @Test @Order(50)
    void EOD_이력_조회_baseDate_status_steps_포함() throws Exception {
        // 20350201 만 필터 → 정확히 COMPLETED 1건이 있어야 한다
        mockMvc.perform(get("/api/internal/eod/history")
                        .param("from", EOD_DUE_DATE).param("to", EOD_DUE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].baseDate").value(EOD_DUE_DATE))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].steps.length()").value(9))
                .andExpect(jsonPath("$.data[0].steps[0].stepName").value("interestAccrualStep"))
                .andExpect(jsonPath("$.data[0].steps[7].stepName").value("accountingSummaryStep"))
                .andExpect(jsonPath("$.data[0].steps[8].stepName").value("notificationFlushStep"));
    }

    @Test @Order(51)
    void EOD_이력_from_to_매칭없음_빈배열() throws Exception {
        mockMvc.perform(get("/api/internal/eod/history")
                        .param("from", "20990101").param("to", "20991231"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ────────────────────────────────────────────
    // Phase 5: 재처리 (restart)
    // ────────────────────────────────────────────

    @Test @Order(60)
    void restart_미존재_baseDate_NOT_FOUND() throws Exception {
        mockMvc.perform(post("/api/internal/eod/restart").param("baseDate", "20990101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("NOT_FOUND"));
    }

    @Test @Order(61)
    void restart_COMPLETED_잡_REJECTED() throws Exception {
        mockMvc.perform(post("/api/internal/eod/restart").param("baseDate", EOD_DUE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("REJECTED"));
    }

    // ────────────────────────────────────────────
    // Phase 6: 잡 종료 알림 (outbox 적재)
    // ────────────────────────────────────────────

    @Test @Order(70)
    void EOD_COMPLETED_outbox_적재_KAFKA_채널() {
        var page = outboxRepository.findByEventTypeCdAndDeletedAtIsNull(
                "LOAN_EOD_COMPLETED", PageRequest.of(0, 50));
        // Order 10·20 두 번 COMPLETED → outbox 2건 이상
        assertThat(page.getContent()).hasSizeGreaterThanOrEqualTo(2);
        var sample = page.getContent().get(0);
        assertThat(sample.getChannelCd()).isEqualTo("KAFKA_DOMAIN_EVENT");
        assertThat(sample.getPayload()).contains("\"baseDate\"");
        assertThat(sample.getPayload()).contains("\"status\": \"COMPLETED\"");
        assertThat(sample.getPayload()).contains("\"steps\"");
    }

    // ────────────────────────────────────────────
    // Phase 8: EOM (월마감) — baseMonth=203502
    // ────────────────────────────────────────────

    @Test @Order(100)
    void EOM_baseMonth_203502_COMPLETED_합계_적재() throws Exception {
        mockMvc.perform(post("/api/internal/eom/run").param("baseMonth", "203502"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("COMPLETED"));

        var summary = monthlySummaryRepository.findBySummaryMonth("203502").orElseThrow();
        assertThat(summary.getBaseMonthStartDate()).isEqualTo("20350201");
        assertThat(summary.getBaseMonthEndDate()).isEqualTo("20350228");
        // 2035-02 안에서 발생한 이자 (2일치)
        assertThat(summary.getInterestRevenue()).isGreaterThan(0L);
        // 2035-02-05 의 연체이자
        assertThat(summary.getOverdueInterestRevenue()).isGreaterThan(0L);
        // 2035-02-01 의 자동이체 1건 (A)
        assertThat(summary.getAutoDebitCount()).isGreaterThanOrEqualTo(1);
        // 월말 시점 ACTIVE 약정 (A, B)
        assertThat(summary.getMonthEndActiveContracts()).isGreaterThanOrEqualTo(2);
        // monthEndNplCount 는 전역 집계라 정확히 0 을 단언할 수 없다.
        // 통합 테스트가 Postgres 컨테이너를 공유하므로 다른 클래스가 만든 연체가 섞인다.
        // 대신 이 테스트 계약(B)이 NPL 이 아님(4일 연체 = STAGE_0)을 직접 확인한다.
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/delinquency", cntrIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dlqStageCd").value("STAGE_0"));
    }

    @Test @Order(101)
    void EOM_동일_baseMonth_재실행_SKIPPED() throws Exception {
        mockMvc.perform(post("/api/internal/eom/run").param("baseMonth", "203502"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("SKIPPED"));
    }

    @Test @Order(102)
    void EOM_baseMonth_형식오류_400() throws Exception {
        mockMvc.perform(post("/api/internal/eom/run").param("baseMonth", "2035-02"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(103)
    void EOM_후_ECL_산출_Stage1_정상_Stage2_연체() {
        // EOM 잡이 monthlyAccountingSummaryStep + eclCalculationStep 모두 실행함
        var rowsByCntr = eclRepository.findBySummaryMonthOrderByCntrIdAsc("203502");
        // A·B 각 1행
        assertThat(rowsByCntr).hasSizeGreaterThanOrEqualTo(2);

        var aRow = rowsByCntr.stream().filter(r -> r.getCntrId().equals(cntrIdA)).findFirst().orElseThrow();
        var bRow = rowsByCntr.stream().filter(r -> r.getCntrId().equals(cntrIdB)).findFirst().orElseThrow();

        // A: 자동이체로 1회차 PAID, 연체 없음 → IFRS STAGE_1, PD=50bps
        assertThat(aRow.getIfrsStageCd()).isEqualTo("STAGE_1");
        assertThat(aRow.getPdBps()).isEqualTo(50);
        assertThat(aRow.getEcl()).isGreaterThanOrEqualTo(0L);

        // B: 4일 연체 (STAGE_0) → IFRS STAGE_2, PD=200bps
        assertThat(bRow.getIfrsStageCd()).isEqualTo("STAGE_2");
        assertThat(bRow.getPdBps()).isEqualTo(200);
        assertThat(bRow.getEcl()).isGreaterThan(0L);
        // B 의 ECL 이 A 보다 큼 (연체 가중치)
        assertThat(bRow.getEcl()).isGreaterThan(aRow.getEcl());
    }

    @Test @Order(90)
    void 일일_회계_요약_적재() {
        // 20350201 — 자동이체 1건 + 이자 발생
        var summary1 = accountingSummaryRepository.findBySummaryDate(EOD_DUE_DATE).orElseThrow();
        assertThat(summary1.getAutoDebitCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary1.getInterestRevenue()).isGreaterThan(0L);
        assertThat(summary1.getActiveContractCount()).isGreaterThanOrEqualTo(2);

        // 20350205 — 연체이자 발생
        var summary2 = accountingSummaryRepository.findBySummaryDate(EOD_OVERDUE).orElseThrow();
        assertThat(summary2.getOverdueInterestRevenue()).isGreaterThan(0L);
        assertThat(summary2.getActiveDelinquencyCount()).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(80)
    void notificationFlushStep_outbox_SENT_전이() {
        // notificationFlushStep 이 EOD 마지막에 돌면서 PENDING 을 SENT 로 보낸다.
        // stub 어댑터(SMS/Email/Kakao) 는 항상 성공 → 대부분 SENT.
        // Kafka 는 testcontainers 라 정상 발행. 따라서 SENT 건수가 양수여야 한다.
        var sentPage = outboxRepository.findByStatusAndDeletedAtIsNull(
                "SENT", PageRequest.of(0, 200));
        assertThat(sentPage.getContent()).isNotEmpty();
    }

    // ──────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────

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
        String code = "EOD_" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "prodCd":"%s","prodName":"EOD 테스트 상품","loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000,"maxAmount":100000000,
                  "minPeriodMo":12,"maxPeriodMo":60,
                  "collateralRequiredYn":"N","guarantorRequiredYn":"N"
                }
                """.formatted(code);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":7001,"prodId":%d,"channelCd":"MOBILE",
                  "requestedAmount":%d,"requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
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
        MvcResult r = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId, String autoDebitYn) throws Exception {
        String body = """
                { "bankCd":"088","accountNo":"1102345678901",
                  "holderName":"홍길동","autoDebitYn":"%s","debitDay":1 }
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
                        .header("Idempotency-Key", "eod-drawdown-" + UUID.randomUUID())
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
