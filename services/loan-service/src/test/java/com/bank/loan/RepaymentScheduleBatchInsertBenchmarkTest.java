package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleJdbcBatchInserter;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상환 스케줄 저장 방식 정량화 — JdbcTemplate.batchUpdate vs JPA saveAll.
 *
 * 회차 수(installment) 별로 동일한 행을 두 경로로 INSERT 하고 소요 시간을 비교한다.
 *   - AFTER : {@link RepaymentScheduleJdbcBatchInserter#batchInsert} (현행, 단일 JDBC 배치)
 *   - BEFORE: {@link RepaymentScheduleRepository#saveAll} (IDENTITY 채번이라 회차 수만큼 개별 insert)
 *
 * 측정 결과는 표로 출력하고, 회차 수가 큰 구간에서 batch 가 saveAll 보다 느리지 않음만 하드 검증한다.
 * (절대 수치/배수는 환경마다 다르므로 단정하지 않는다.)
 *
 * ⚠️ 주의: 결과는 JDBC 옵션에 좌우된다. PostgreSQL 드라이버의 reWriteBatchedInserts=true 가 켜지면
 * 배치가 멀티-row INSERT 로 재작성돼 batch 우위가 더 커진다. 본 테스트는 현재 datasource 설정을
 * 그대로 사용하므로, 수치를 인용할 땐 활성 JDBC URL 옵션을 함께 명시할 것.
 *
 * repayment_schedule.cntr_id 는 loan_contract 로의 FK 라, drawdown 하지 않은 약정 1건을 만들어
 * 모든 측정에 재사용한다(스케줄은 drawdown 부수효과라 약정만 만들면 회차 행은 비어 있음).
 * 측정 회차마다 repayment_schedule 행을 지워 (cntr_id, installment_no, version) 유니크 충돌을 피한다.
 */
class RepaymentScheduleBatchInsertBenchmarkTest extends AbstractLoanIntegrationTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 측정할 회차 수 — 신용(12) ~ 주담대 최장(360). */
    private static final int[] INSTALLMENT_COUNTS = {12, 36, 60, 120, 360};

    private static final int WARMUP_RUNS  = 1;
    private static final int MEASURE_RUNS = 3;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    @Autowired private RepaymentScheduleJdbcBatchInserter batchInserter;
    @Autowired private RepaymentScheduleRepository scheduleRepository;
    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private JdbcTemplate jdbc;

    /** drawdown 하지 않은 약정 — 모든 측정이 공유. */
    private Long cntrId;

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    @Test
    void 회차수별_batchUpdate_vs_saveAll_저장시간_정량화() {
        System.out.println();
        System.out.println("=== 상환 스케줄 저장 시간: batchUpdate(AFTER) vs saveAll(BEFORE) ===");
        System.out.printf("%-8s | %14s | %14s | %8s%n",
                "회차수", "batch(min ms)", "saveAll(min ms)", "배수");
        System.out.println("---------+----------------+----------------+---------");

        long batchMinLargest   = Long.MAX_VALUE;
        long saveAllMinLargest = Long.MAX_VALUE;
        int largestCount = INSTALLMENT_COUNTS[INSTALLMENT_COUNTS.length - 1];

        for (int count : INSTALLMENT_COUNTS) {
            long batchMin   = measure(count, /* useBatch */ true);
            long saveAllMin = measure(count, /* useBatch */ false);

            double batchMs   = batchMin   / 1_000_000.0;
            double saveAllMs = saveAllMin / 1_000_000.0;
            double ratio     = batchMin == 0 ? 0 : (double) saveAllMin / batchMin;

            System.out.printf("%-8d | %14.2f | %14.2f | %7.1fx%n",
                    count, batchMs, saveAllMs, ratio);

            if (count == largestCount) {
                batchMinLargest   = batchMin;
                saveAllMinLargest = saveAllMin;
            }
        }
        System.out.println("================================================================");

        // 가장 큰 회차 구간에서 batch 가 saveAll 보다 느리지 않아야 한다 (수치 단정 대신 우열만 검증).
        assertThat(batchMinLargest)
                .as("회차 %d개에서 batchUpdate 가 saveAll 보다 빠르거나 같아야 함", largestCount)
                .isLessThanOrEqualTo(saveAllMinLargest);
    }

    /** 주어진 방식으로 count 회차를 WARMUP 후 MEASURE_RUNS 번 측정, 최소 소요시간(ns)을 반환. */
    private long measure(int count, boolean useBatch) {
        for (int w = 0; w < WARMUP_RUNS; w++) {
            runOnce(count, useBatch);
        }
        long min = Long.MAX_VALUE;
        for (int r = 0; r < MEASURE_RUNS; r++) {
            min = Math.min(min, runOnce(count, useBatch));
        }
        return min;
    }

    /** 한 번 측정: 행 생성 → INSERT 시간 측정 → 검증 → 정리. */
    private long runOnce(int count, boolean useBatch) {
        List<RepaymentSchedule> rows = buildRows(count);

        long start = System.nanoTime();
        if (useBatch) {
            batchInserter.batchInsert(rows);
        } else {
            scheduleRepository.saveAll(rows);
        }
        long elapsed = System.nanoTime() - start;

        Integer inserted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repayment_schedule WHERE cntr_id = ?", Integer.class, cntrId);
        assertThat(inserted).isEqualTo(count);

        jdbc.update("DELETE FROM repayment_schedule WHERE cntr_id = ?", cntrId);
        return elapsed;
    }

    private List<RepaymentSchedule> buildRows(int count) {
        List<RepaymentSchedule> rows = new ArrayList<>(count);
        LocalDate firstDue = LocalDate.of(2090, 1, 1);
        long remaining = 1_000_000L * count;
        for (int i = 0; i < count; i++) {
            int installmentNo = i + 1;
            remaining -= 1_000_000L;
            rows.add(RepaymentSchedule.builder()
                    .cntrId(cntrId)
                    .installmentNo(installmentNo)
                    .dueDate(firstDue.plusMonths(installmentNo).format(DATE))
                    .scheduledPrincipal(1_000_000L)
                    .scheduledInterest(50_000L)
                    .scheduledTotal(1_050_000L)
                    .remainingBalance(Math.max(remaining, 0L))
                    .appliedRateBps(RATE_BPS)
                    .rschStatusCd(RepaymentSchedule.STATUS_DUE)
                    .rschVersionCd(RepaymentSchedule.VERSION_INITIAL)
                    .holidayAdjustedYn(false)
                    .build());
        }
        return rows;
    }

    // ============================================================
    // 약정 셋업 helpers (RepaymentScheduleFlowTest 와 동일 패턴, drawdown 생략)
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "BENCH_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"배치insert 벤치 상품", "loanTypeCd":"CREDIT",
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
}
