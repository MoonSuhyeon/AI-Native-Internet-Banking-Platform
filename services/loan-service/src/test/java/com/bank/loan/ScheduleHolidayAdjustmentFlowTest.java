package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 05 step 3: 스케줄 생성 시 휴일 보정 회귀. 연도 2037.
 *
 * 검증 대상:
 *   1) 주말 fallback — Sat/Sun due 회차가 다음 영업일로 이동, holidayAdjustedYn = 'Y'
 *   2) 영업일 회차는 보정 없이 'N' 유지
 *   3) BULLET 도 동일하게 보정
 *   4) business_calendar 에 등록된 평일 휴일도 보정에 반영
 *
 * 회차 due_date 는 cntr_start_date.plusMonths(installmentNo) 이므로
 * cntrStartDate=20370101 → 회차 1..12 due 20370201, 20370301, ... , 20380101.
 *
 * 2037 년 매월 1일 요일:
 *   02-01 Sun*, 03-01 Sun*, 04-01 Wed, 05-01 Fri, 06-01 Mon, 07-01 Wed,
 *   08-01 Sat*, 09-01 Tue, 10-01 Thu, 11-01 Sun*, 12-01 Tue, 38-01-01 Fri
 *   → 4건이 Y 로 이동 (별표).
 */
class ScheduleHolidayAdjustmentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private RepaymentScheduleRepository scheduleRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    @Test
    void EQUAL_생성_시_주말_회차는_다음_월요일로_보정된다() throws Exception {
        Long cntrId = setupContract("EQUAL", "20370101");
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        List<RepaymentSchedule> rows = scheduleRepository
                .findByCntrIdAndRschVersionCdAndDeletedAtIsNullOrderByInstallmentNoAsc(
                        cntrId, RepaymentSchedule.VERSION_INITIAL);
        assertThat(rows).hasSize(PERIOD_MONTHS);

        // 1: 20370201 Sun → 20370202 Mon
        assertShifted(rows.get(0),  "20370202");
        // 2: 20370301 Sun → 20370302 Mon
        assertShifted(rows.get(1),  "20370302");
        // 3: 20370401 Wed unchanged
        assertNotShifted(rows.get(2), "20370401");
        // 4: 20370501 Fri unchanged
        assertNotShifted(rows.get(3), "20370501");
        // 5: 20370601 Mon unchanged
        assertNotShifted(rows.get(4), "20370601");
        // 6: 20370701 Wed unchanged
        assertNotShifted(rows.get(5), "20370701");
        // 7: 20370801 Sat → 20370803 Mon
        assertShifted(rows.get(6), "20370803");
        // 8: 20370901 Tue unchanged
        assertNotShifted(rows.get(7), "20370901");
        // 9: 20371001 Thu unchanged
        assertNotShifted(rows.get(8), "20371001");
        // 10: 20371101 Sun → 20371102 Mon
        assertShifted(rows.get(9), "20371102");
        // 11: 20371201 Tue unchanged
        assertNotShifted(rows.get(10), "20371201");
        // 12: 20380101 Fri unchanged
        assertNotShifted(rows.get(11), "20380101");

        long shifted = rows.stream().filter(RepaymentSchedule::isHolidayAdjusted).count();
        assertThat(shifted).isEqualTo(4);
    }

    @Test
    void BULLET_생성_시도_동일한_정책으로_보정된다() throws Exception {
        Long cntrId = setupContract("BULLET", "20370101");
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        List<RepaymentSchedule> rows = scheduleRepository
                .findByCntrIdAndRschVersionCdAndDeletedAtIsNullOrderByInstallmentNoAsc(
                        cntrId, RepaymentSchedule.VERSION_INITIAL);
        assertThat(rows).hasSize(PERIOD_MONTHS);

        assertShifted(rows.get(0), "20370202");      // 02-01 Sun
        assertShifted(rows.get(1), "20370302");      // 03-01 Sun
        assertNotShifted(rows.get(5), "20370701");   // 07-01 Wed
        assertShifted(rows.get(6), "20370803");      // 08-01 Sat
        assertShifted(rows.get(9), "20371102");      // 11-01 Sun
        assertNotShifted(rows.get(11), "20380101");  // 38-01-01 Fri
    }

    @Test
    void 등록된_평일_휴일도_다음_영업일로_보정된다() throws Exception {
        // 20370605 (Fri) 를 임시공휴일로 등록 — Fri,Sat,Sun 모두 비영업일이므로 다음 영업일은 20370608 (Mon).
        registerHoliday("20370605", "TEMP_HOLIDAY", "테스트 임시휴일");

        // cntr_start_date 20370505 → 회차 1 due 20370605 → 20370608 로 보정
        Long cntrId = setupContract("EQUAL", "20370505");
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        List<RepaymentSchedule> rows = scheduleRepository
                .findByCntrIdAndRschVersionCdAndDeletedAtIsNullOrderByInstallmentNoAsc(
                        cntrId, RepaymentSchedule.VERSION_INITIAL);
        assertShifted(rows.get(0), "20370608");
    }

    private void assertShifted(RepaymentSchedule row, String expectedDueDate) {
        assertThat(row.getDueDate()).isEqualTo(expectedDueDate);
        assertThat(row.isHolidayAdjusted()).isTrue();
        assertThat(row.getHolidayAdjustedYn()).isEqualTo(RepaymentSchedule.YN_Y);
    }

    private void assertNotShifted(RepaymentSchedule row, String expectedDueDate) {
        assertThat(row.getDueDate()).isEqualTo(expectedDueDate);
        assertThat(row.isHolidayAdjusted()).isFalse();
        assertThat(row.getHolidayAdjustedYn()).isEqualTo(RepaymentSchedule.YN_N);
    }

    private void registerHoliday(String calDate, String typeCd, String name) throws Exception {
        String body = """
                {
                  "calDate":"%s",
                  "businessDayYn":"N",
                  "holidayTypeCd":"%s",
                  "holidayName":"%s",
                  "baseCountryCd":"KR"
                }
                """.formatted(calDate, typeCd, name);
        mockMvc.perform(post("/api/business-calendar")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private Long setupContract(String method, String cntrStartDate) throws Exception {
        Long prodId = createProduct(method);
        activateProduct(prodId);
        Long applId = createApplication(prodId, method);
        forceApprove(applId);
        Long cntrId = createContract(applId, method, cntrStartDate);
        registerAndVerifyRepaymentAccount(cntrId);
        return cntrId;
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        String body = """
                {
                  "executedAmount":%d,
                  "disbursementBankCd":"088",
                  "disbursementAccountNo":"1109999998888"
                }
                """.formatted(amount);
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "holiday-shift-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct(String method) throws Exception {
        String code = "HOLI_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"휴일 보정 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"%s", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
                }
                """.formatted(code, method);
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

    private Long createApplication(Long prodId, String method) throws Exception {
        String body = """
                {
                  "customerId":5801, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"%s"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS, method);
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

    private Long createContract(Long applId, String method, String cntrStartDate) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"%s",
                  "cntrStartDate":"%s"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, method, cntrStartDate);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901", "holderName":"홍길동",
                  "autoDebitYn":"Y", "debitDay":15 }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}
