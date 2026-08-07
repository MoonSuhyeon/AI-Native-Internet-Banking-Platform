package com.bank.loan;

import com.bank.loan.accounting.repository.AccountingSummaryQuery;
import com.bank.loan.accounting.repository.AccountingSummaryQuery.RepaymentTxnSummary;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회계 요약 통계 집계 방식 정량화 — 인메모리(Java stream) 집계 vs DB 집계(SQL SUM/COUNT).
 *
 * 동일한 합계(자동이체 원금/이자/연체이자/건수)를 두 경로로 구하고 데이터 건수별 응답시간을 비교한다.
 *   - BEFORE(in-memory): 대상 행을 전부 앱으로 끌어와 Java stream 으로 필터·합산
 *   - AFTER (SQL aggr) : {@link AccountingSummaryQuery#sumAutoDebit} — DB 가 SUM/COUNT 하고 1행만 반환
 *
 * ⚠️ 주의: 결과는 인덱스 유무에 좌우된다. repayment_transaction.value_date 에는 기본 인덱스가 없어
 * WHERE value_date=? 가 seq scan 으로 풀린다. 인덱스를 더하면 DB 집계가 노이즈 행을 건너뛰어 더 빨라진다.
 * 이를 직접 보이기 위해 DB 집계를 인덱스 無/有 두 번 측정한다.
 *
 * 인메모리 측정은 JdbcTemplate 로 행만 끌어와 Java 에서 합산하므로, JPA 엔티티 하이드레이션까지 거치는
 * 실제 "before" 보다 보수적(=더 빠른) 하한이다. 그럼에도 DB 집계와 큰 격차가 난다.
 *
 * FK 때문에 약정 1건을 만들고 그 아래에 거래 행을 seed 한다. 대상(BASE_DATE) 행 N개 + 노이즈 행 4N개를
 * 섞어, value_date 필터가 소수만 고르도록 해 인덱스 효과가 드러나게 한다.
 * 다른 통합테스트와 충돌하지 않도록 value_date 는 2090년대를 쓴다.
 */
class AccountingSummaryAggregationBenchmarkTest extends AbstractLoanIntegrationTest {

    private static final String BASE_DATE = "20900101";
    private static final String[] NOISE_DATES = {"20900102", "20900103", "20900104", "20900105"};

    /** 대상(매칭) 행 수 — 전체는 5배(노이즈 4배 포함). */
    private static final int[] MATCH_COUNTS = {1_000, 5_000, 10_000};
    private static final int NOISE_MULTIPLIER = 4;

    private static final int WARMUP_RUNS  = 1;
    private static final int MEASURE_RUNS = 3;

    private static final String BENCH_INDEX = "idx_bench_rtx_value_date";

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private static final long P = 1_000L;   // 회당 원금
    private static final long I = 100L;     // 회당 이자
    private static final long O = 10L;      // 회당 연체이자

    @Autowired private AccountingSummaryQuery summaryQuery;
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
    }

    @Test
    void 데이터건수별_인메모리_vs_DB집계_응답시간_정량화() {
        dropIndex();        // 혹시 남아있을 수 있는 벤치 인덱스 제거
        alignRtxSequence(); // 데모 시드가 명시 PK 로 넣어 뒤처진 BIGSERIAL 시퀀스를 MAX(rtx_id)+1 로 정렬

        int n = MATCH_COUNTS.length;
        long[] inMemNs   = new long[n];
        long[] sqlNoIdxNs = new long[n];
        long[] sqlIdxNs   = new long[n];

        // ── 1단계: 인덱스 없이 in-memory vs SQL 집계 ──
        for (int k = 0; k < n; k++) {
            int match = MATCH_COUNTS[k];
            seed(match);
            verifyBothAgree(match);                      // 두 경로가 같은 값을 내는지 1회 검증
            inMemNs[k]   = measure(this::inMemoryAggregate);
            sqlNoIdxNs[k] = measure(() -> summaryQuery.sumAutoDebit(BASE_DATE));
            clean();
        }

        // ── 2단계: value_date 인덱스 추가 후 SQL 집계 ──
        createIndex();
        try {
            for (int k = 0; k < n; k++) {
                int match = MATCH_COUNTS[k];
                seed(match);
                jdbc.execute("ANALYZE repayment_transaction"); // 플래너가 인덱스를 선택하도록 통계 갱신
                sqlIdxNs[k] = measure(() -> summaryQuery.sumAutoDebit(BASE_DATE));
                clean();
            }
        } finally {
            dropIndex();
        }

        System.out.println();
        System.out.println("=== 회계 통계 집계: 인메모리(BEFORE) vs DB집계(AFTER) — 데이터 건수별 ===");
        System.out.printf("%-8s | %8s | %12s | %14s | %14s | %10s%n",
                "매칭행", "전체행", "인메모리(ms)", "DB집계無idx(ms)", "DB집계有idx(ms)", "인메모리/有idx");
        System.out.println("---------+----------+--------------+----------------+----------------+-----------");
        for (int k = 0; k < n; k++) {
            int match = MATCH_COUNTS[k];
            int total = match * (1 + NOISE_MULTIPLIER);
            double inMemMs = inMemNs[k]   / 1_000_000.0;
            double noIdxMs = sqlNoIdxNs[k] / 1_000_000.0;
            double idxMs   = sqlIdxNs[k]   / 1_000_000.0;
            double ratio   = sqlIdxNs[k] == 0 ? 0 : (double) inMemNs[k] / sqlIdxNs[k];
            System.out.printf("%-8d | %8d | %12.2f | %14.2f | %14.2f | %9.1fx%n",
                    match, total, inMemMs, noIdxMs, idxMs, ratio);
        }
        System.out.println("==========================================================================");

        // 가장 큰 데이터 구간에서 DB 집계(인덱스)가 인메모리보다 빨라야 한다.
        int last = n - 1;
        assertThat(sqlIdxNs[last])
                .as("매칭 %d행에서 DB집계(인덱스)가 인메모리보다 빨라야 함", MATCH_COUNTS[last])
                .isLessThan(inMemNs[last]);
    }

    /** 인메모리 집계: 대상 약정 행을 전부 끌어와 Java 에서 필터·합산 (BEFORE 경로 재현). */
    private RepaymentTxnSummary inMemoryAggregate() {
        record Row(long principal, long interest, long overdue,
                   String channel, String status, boolean reversal, String valueDate, boolean deleted) {}
        List<Row> rows = jdbc.query("""
                SELECT principal_amount, interest_amount, overdue_interest_amount,
                       channel_cd, rtx_status_cd, reversal_yn, value_date, deleted_at
                  FROM repayment_transaction
                 WHERE cntr_id = ?
                """, (rs, i) -> new Row(
                        rs.getLong("principal_amount"),
                        rs.getLong("interest_amount"),
                        rs.getLong("overdue_interest_amount"),
                        rs.getString("channel_cd"),
                        rs.getString("rtx_status_cd"),
                        rs.getBoolean("reversal_yn"),
                        rs.getString("value_date"),
                        rs.getObject("deleted_at") != null),
                cntrId);

        long principal = 0, interest = 0, overdue = 0;
        int count = 0;
        for (Row r : rows) {
            if (BASE_DATE.equals(r.valueDate())
                    && "AUTO_DEBIT".equals(r.channel())
                    && "SUCCESS".equals(r.status())
                    && !r.reversal()
                    && !r.deleted()) {
                principal += r.principal();
                interest  += r.interest();
                overdue   += r.overdue();
                count++;
            }
        }
        return new RepaymentTxnSummary(principal, interest, overdue, count);
    }

    private void verifyBothAgree(int match) {
        RepaymentTxnSummary mem = inMemoryAggregate();
        RepaymentTxnSummary sql = summaryQuery.sumAutoDebit(BASE_DATE);
        assertThat(mem.count()).isEqualTo(match);
        assertThat(sql.count()).isEqualTo(match);
        assertThat(sql.principal()).isEqualTo(mem.principal()).isEqualTo(P * match);
        assertThat(sql.interest()).isEqualTo(mem.interest());
        assertThat(sql.overdueInterest()).isEqualTo(mem.overdueInterest());
    }

    private long measure(Runnable r) {
        for (int w = 0; w < WARMUP_RUNS; w++) r.run();
        long min = Long.MAX_VALUE;
        for (int m = 0; m < MEASURE_RUNS; m++) {
            long start = System.nanoTime();
            r.run();
            min = Math.min(min, System.nanoTime() - start);
        }
        return min;
    }

    /** 대상 match 행(BASE_DATE) + 노이즈 4*match 행(다른 날짜) 을 batch insert. */
    private void seed(int match) {
        int total = match * (1 + NOISE_MULTIPLIER);
        final String sql = """
                INSERT INTO repayment_transaction (
                    cntr_id, rtx_type_cd, total_amount,
                    principal_amount, interest_amount, overdue_interest_amount,
                    channel_cd, rtx_status_cd, value_date, reversal_yn,
                    created_by, updated_by
                ) VALUES (?, 'REPAYMENT', ?, ?, ?, ?, 'AUTO_DEBIT', 'SUCCESS', ?, false, 1, 1)
                """;
        // value_date 목록을 미리 만든다: 앞 match개는 BASE_DATE, 나머지는 노이즈 날짜를 순환.
        List<String> dates = new ArrayList<>(total);
        for (int i = 0; i < match; i++) dates.add(BASE_DATE);
        for (int i = match; i < total; i++) dates.add(NOISE_DATES[i % NOISE_DATES.length]);

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setLong(1, cntrId);
                ps.setLong(2, P + I + O);
                ps.setLong(3, P);
                ps.setLong(4, I);
                ps.setLong(5, O);
                ps.setString(6, dates.get(i));
            }
            @Override public int getBatchSize() { return total; }
        });
    }

    private void clean() {
        jdbc.update("DELETE FROM repayment_transaction WHERE cntr_id = ?", cntrId);
    }

    private void alignRtxSequence() {
        jdbc.queryForObject(
                "SELECT setval(pg_get_serial_sequence('repayment_transaction','rtx_id'), "
                        + "(SELECT COALESCE(MAX(rtx_id), 1) FROM repayment_transaction))",
                Long.class);
    }

    private void createIndex() {
        jdbc.execute("CREATE INDEX " + BENCH_INDEX + " ON repayment_transaction (value_date)");
    }

    private void dropIndex() {
        jdbc.execute("DROP INDEX IF EXISTS " + BENCH_INDEX);
    }

    // ============================================================
    // 약정 셋업 helpers (drawdown 생략)
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "AGGR_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"통계집계 벤치 상품", "loanTypeCd":"CREDIT",
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
