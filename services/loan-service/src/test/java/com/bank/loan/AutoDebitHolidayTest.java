package com.bank.loan;

import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * autodebit 휴일 보정 회귀 테스트 (연도 2037).
 *
 * 세팅: 20370101(신정)을 공휴일로 등록, cntrStartDate=20361201
 *   - 계약 A: 신규 약정 → 1회차 due 20370101 → 20370102(금)로 보정
 *   - 계약 B: 구약정 시뮬레이션 → 동일 start 후 dueDate를 20370101로 직접 복원
 *
 * 시나리오:
 *   10) A 1회차 dueDate=20370102, holidayAdjustedYn=Y
 *   20) baseDate=20370101(공휴일) → NON_BUSINESS_DAY 스킵
 *   30) baseDate=20370102 → A·B 모두 처리 (range: dueDate ∈ (20361231, 20370102])
 *   40) baseDate=20370102 재실행 → 0건 (멱등)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoDebitHolidayTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private RepaymentScheduleRepository scheduleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String CNTR_START_DATE   = "20361201";
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 500;

    private Long cntrIdA;
    private Long cntrIdB;

    @BeforeAll
    void setup() throws Exception {
        mockMvc.perform(post("/api/business-calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"calDate":"20370101","businessDayYn":false,
                                 "holidayTypeCd":"PUBLIC","holidayName":"신정"}
                                """))
                .andExpect(status().isCreated());

        cntrIdA = setupContract();
        cntrIdB = setupContract();

        // B 의 1회차 dueDate 를 공휴일(20370101)로 되돌려 미보정 구약정 시뮬레이션
        jdbcTemplate.update(
                "UPDATE repayment_schedule SET due_date = '20370101', holiday_adjusted_yn = 'N' " +
                "WHERE cntr_id = ? AND installment_no = 1 AND rsch_version_cd = 'V1' AND deleted_at IS NULL",
                cntrIdB);
    }

    @Test @Order(10)
    void A_1회차_dueDate_보정확인() {
        RepaymentSchedule s = scheduleRepository
                .findByCntrIdAndInstallmentNoAndRschVersionCdAndDeletedAtIsNull(
                        cntrIdA, 1, RepaymentSchedule.VERSION_INITIAL)
                .orElseThrow();
        assertThat(s.getDueDate()).isEqualTo("20370102");
        assertThat(s.getHolidayAdjustedYn()).isTrue();
    }

    @Test @Order(20)
    void 공휴일_baseDate_NON_BUSINESS_DAY_스킵() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", "20370101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skipReason").value("NON_BUSINESS_DAY"))
                .andExpect(jsonPath("$.data.totalCandidates").value(0));
    }

    @Test @Order(30)
    void 익영업일_배치_A_B_모두_처리() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", "20370102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseDate").value("20370102"))
                .andExpect(jsonPath("$.data.totalCandidates").value(2))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test @Order(40)
    void 익영업일_재실행_멱등() throws Exception {
        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", "20370102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCandidates").value(0))
                .andExpect(jsonPath("$.data.processed").value(0));
    }

    // ============================================================
    // helpers
    // ============================================================

    private Long setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        Long cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId);
        return cntrId;
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String body = """
                {"prodCd":"%s","prodName":"휴일보정테스트","loanTypeCd":"CREDIT",
                 "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":%d,
                 "minAmount":1000000,"maxAmount":100000000,
                 "minPeriodMo":12,"maxPeriodMo":60,
                 "collateralRequiredYn":false,"guarantorRequiredYn":false}
                """.formatted("HC_" + uniq(), RATE_BPS);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{id}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {"customerId":6001,"prodId":%d,"channelCd":"MOBILE",
                 "requestedAmount":%d,"requestedPeriodMo":%d,
                 "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"}
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void forceApprove(Long applId) {
        var app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private Long createContract(Long applId) throws Exception {
        String body = """
                {"applId":%d,"contractedAmount":%d,"contractedPeriodMo":%d,
                 "baseRateBps":%d,"rateTypeCd":"FIXED","repaymentMethodCd":"EQUAL",
                 "cntrStartDate":"%s"}
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, CNTR_START_DATE);
        MvcResult r = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{id}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bankCd":"088","accountNo":"1102345678901","holderName":"테스터",
                                 "autoDebitYn":true,"debitDay":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{id}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{id}/executions", cntrId)
                        .header("Idempotency-Key", "hday-draw-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executedAmount":%d,"disbursementBankCd":"088",
                                 "disbursementAccountNo":"1109999998888"}
                                """.formatted(CONTRACTED_AMOUNT)))
                .andExpect(status().isCreated());
    }
}
