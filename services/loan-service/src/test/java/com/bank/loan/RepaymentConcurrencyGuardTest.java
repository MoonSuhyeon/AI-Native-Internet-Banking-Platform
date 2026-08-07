package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동시 상환 중복 차단 정확성 검증 — 성능(시간)이 아니라 "중복 0건 / N요청" 정확성 지표.
 *
 * 같은 회차(installment)에 서로 다른 Idempotency-Key 로 N개 요청을 동시에 쏜다(더블 서밋 레이스).
 * 가드는 {@link com.bank.loan.schedule.repository.RepaymentScheduleRepository#claimStatusChange}
 * — DUE/OVERDUE→PAID 조건부 UPDATE 가 DB 행 잠금으로 직렬화되어 오직 1요청만 선점한다.
 *
 * 회차마다 다음을 하드 검증한다:
 *   - HTTP 201(성공) 정확히 1건, 409 LOAN_091(차단) 나머지 전부, 그 외 응답 0건
 *   - 해당 회차(rsch)에 대한 SUCCESS 상환거래 row 가 정확히 1건 (이중 출금 없음 = 중복 0)
 *   - 회차 상태 PAID
 *
 * 여러 회차로 레이스를 반복해 "총 요청 / 성공 / 차단 / 중복" 집계를 출력한다.
 *
 * 참고: 풀 크기로 동시성이 줄어 일부 요청이 직렬화돼도 결과는 동일해야 한다 — 직렬이든 병렬이든
 * 선점은 1건뿐이라는 게 핵심. 즉 이 테스트는 동시성 '강도'가 아니라 가드의 '정확성'을 본다.
 */
class RepaymentConcurrencyGuardTest extends AbstractLoanIntegrationTest {

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    /** 회차당 동시 요청 수. */
    private static final int CONCURRENCY = 16;
    /** 레이스를 반복할 회차들 (drawdown 으로 12회차 생성됨). */
    private static final int[] RACE_INSTALLMENTS = {1, 2, 3, 4, 5};

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private JdbcTemplate jdbc;

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

    @Test
    void 동시_상환_요청은_회차당_정확히_1건만_성공하고_중복은_0건() throws Exception {
        int totalRequests = 0, totalSuccess = 0, totalBlocked = 0, totalDuplicates = 0;

        System.out.println();
        System.out.println("=== 동시 상환 중복 차단 정확성 (중복 0건 / N요청) ===");
        System.out.printf("%-6s | %10s | %8s | %8s | %8s | %10s%n",
                "회차", "동시요청", "201성공", "409차단", "그외", "SUCCESS행");
        System.out.println("-------+------------+----------+----------+----------+-----------");

        for (int installmentNo : RACE_INSTALLMENTS) {
            RaceResult r = runRace(installmentNo);
            int successRows = countSuccessTx(installmentNo);
            String paidStatus = scheduleStatus(installmentNo);

            // 정확성 하드 검증
            assertThat(r.created())
                    .as("회차 %d: 동시요청 중 201 성공은 정확히 1건", installmentNo)
                    .isEqualTo(1);
            assertThat(r.other())
                    .as("회차 %d: 예상 외 응답(타임아웃/오류)은 0건이어야 함", installmentNo)
                    .isZero();
            assertThat(r.conflict())
                    .as("회차 %d: 나머지는 모두 409 LOAN_091 로 차단", installmentNo)
                    .isEqualTo(CONCURRENCY - 1);
            assertThat(successRows)
                    .as("회차 %d: SUCCESS 상환거래 row 는 정확히 1건 (중복 출금 없음)", installmentNo)
                    .isEqualTo(1);
            assertThat(paidStatus)
                    .as("회차 %d: 스케줄 상태 PAID", installmentNo)
                    .isEqualTo("PAID");

            int duplicates = Math.max(successRows - 1, 0); // 1 초과 SUCCESS = 중복 출금
            System.out.printf("%-6d | %10d | %8d | %8d | %8d | %10d%n",
                    installmentNo, CONCURRENCY, r.created(), r.conflict(), r.other(), successRows);

            totalRequests   += CONCURRENCY;
            totalSuccess    += r.created();
            totalBlocked    += r.conflict();
            totalDuplicates += duplicates;
        }

        System.out.println("-----------------------------------------------------------------");
        System.out.printf("총계: 요청 %d | 성공 %d | 차단 %d | 중복 %d%n",
                totalRequests, totalSuccess, totalBlocked, totalDuplicates);
        System.out.println("=================================================================");

        // 최종 정확성 지표: 어떤 회차에서도 이중 출금(중복)이 발생하지 않아야 한다.
        assertThat(totalDuplicates).as("총 중복 출금 건수").isZero();
        assertThat(totalSuccess).as("성공 = 회차 수").isEqualTo(RACE_INSTALLMENTS.length);
        assertThat(totalBlocked).as("차단 = 회차 수 × (동시요청-1)")
                .isEqualTo(RACE_INSTALLMENTS.length * (CONCURRENCY - 1));
    }

    /** 한 회차에 CONCURRENCY 개 요청을 동시에 발사하고 HTTP 응답 분포를 집계. */
    private RaceResult runRace(int installmentNo) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>(CONCURRENCY);
            String body = "{ \"installmentNo\":" + installmentNo + ", \"channelCd\":\"MANUAL\" }";

            for (int i = 0; i < CONCURRENCY; i++) {
                final String key = "rc-" + installmentNo + "-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    startGate.await(); // 모든 스레드를 같은 출발선에 정렬
                    try {
                        return mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayments", cntrId)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                                .andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        return -1;
                    }
                }));
            }

            startGate.countDown(); // 일제히 출발
            int created = 0, conflict = 0, other = 0;
            for (Future<Integer> f : futures) {
                int s = f.get();
                if (s == 201) created++;
                else if (s == 409) conflict++;
                else other++;
            }
            return new RaceResult(created, conflict, other);
        } finally {
            pool.shutdownNow();
        }
    }

    private int countSuccessTx(int installmentNo) {
        Long rschId = jdbc.queryForObject("""
                SELECT rsch_id FROM repayment_schedule
                 WHERE cntr_id = ? AND installment_no = ? AND rsch_version_cd = 'V1'
                """, Long.class, cntrId, installmentNo);
        Integer cnt = jdbc.queryForObject("""
                SELECT COUNT(*) FROM repayment_transaction
                 WHERE rsch_id = ? AND rtx_status_cd = 'SUCCESS' AND reversal_yn = 'N' AND deleted_at IS NULL
                """, Integer.class, rschId);
        return cnt == null ? 0 : cnt;
    }

    private String scheduleStatus(int installmentNo) {
        return jdbc.queryForObject("""
                SELECT rsch_status_cd FROM repayment_schedule
                 WHERE cntr_id = ? AND installment_no = ? AND rsch_version_cd = 'V1'
                """, String.class, cntrId, installmentNo);
    }

    private record RaceResult(int created, int conflict, int other) {}

    // ============================================================
    // 약정 셋업 helpers (RepaymentFlowTest 와 동일 패턴)
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "RCONC_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"동시상환 가드 상품", "loanTypeCd":"CREDIT",
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
                        .header("Idempotency-Key", "rc-init-drawdown-" + UUID.randomUUID())
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
