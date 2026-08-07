package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.execution.domain.LoanExecution;
import com.bank.loan.execution.repository.LoanExecutionRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자금 인출(loan_execution / drawdown) 통합 테스트.
 *
 * 흐름:
 *   1)  상품 → ACTIVE → 신청 → APPROVED 강제 → 약정 → 상환계좌 등록 + 검증
 *   10) 미존재 cntrId → 404 LOAN_062
 *   11) executedAmount 누락 → 400 COMMON_400 (Bean Validation)
 *   20) 첫 인출 → 201 DONE, cumulativeExecutedAmount=요청금액, 계약 SIGNED→ACTIVE 전이
 *   21) 추가 인출 → 누적금액 증가
 *   22) 한도 초과 → 400 LOAN_064
 *   30) 멱등성 — 동일 Idempotency-Key 재호출 시 새 row 가 안 생기고 기존 응답 반환
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanExecutionFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;
    @Autowired
    private LoanExecutionRepository executionRepository;

    private static final long CONTRACTED_AMOUNT = 10_000_000L;
    private static final int PERIOD_MONTHS = 24;

    private Long prodId;
    private Long applId;
    private Long cntrId;

    private String firstIdempotencyKey;
    private long firstExecId;
    private long firstCumulative;

    @BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
    }

    // ============================================================
    // 검증 실패
    // ============================================================

    @Test @Order(10)
    void 미존재_계약_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", 999_999_999L)
                        .header("Idempotency-Key", "exec-nf-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(11)
    void executedAmount_누락_400() throws Exception {
        String body = """
                {
                  "disbursementBankCd":"088",
                  "disbursementAccountNo":"1109999998888"
                }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "exec-bad-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_400"))
                .andExpect(jsonPath("$.data.errors[?(@.field == 'executedAmount')]").exists());
    }

    // ============================================================
    // 인출 — 정상
    // ============================================================

    @Test @Order(20)
    void 첫_인출_계약_ACTIVE_전이() throws Exception {
        firstIdempotencyKey = "exec-first-" + UUID.randomUUID();
        long amount = 4_000_000L;
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", firstIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.executedAmount").value(amount))
                .andExpect(jsonPath("$.data.cumulativeExecutedAmount").value(amount))
                .andExpect(jsonPath("$.data.disbursementAccountMasked").exists())
                .andExpect(jsonPath("$.data.journalEntryNo").exists())
                .andExpect(jsonPath("$.data.executedAt").exists())
                .andReturn();
        JsonNode data = extractData(result);
        firstExecId = data.get("execId").asLong();
        firstCumulative = data.get("cumulativeExecutedAmount").asLong();
        assertThat(firstExecId).isPositive();
    }

    @Test @Order(21)
    void 추가_인출_누적금액_증가() throws Exception {
        long amount = 3_000_000L;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "exec-second-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.cumulativeExecutedAmount").value(firstCumulative + amount));
    }

    @Test @Order(22)
    void 한도_초과_400() throws Exception {
        // 누적 7_000_000 — 남은 한도 3_000_000. 4_000_000 요청 시 초과.
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "exec-over-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(4_000_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_064"));
    }

    // ============================================================
    // 멱등성
    // ============================================================

    @Test @Order(30)
    void 동일_Idempotency_Key_재호출_기존응답_반환() throws Exception {
        // 첫 인출(amount=4_000_000) 과 동일 키 + 다른 금액으로 재호출 — 새 row 가 생기지 않고
        // 기존 row 가 그대로 반환되어야 한다.
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", firstIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(9_999_999L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execId").value(firstExecId))
                .andExpect(jsonPath("$.data.executedAmount").value(4_000_000))
                .andReturn();
        JsonNode data = extractData(result);
        assertThat(data.get("execId").asLong()).isEqualTo(firstExecId);
    }

    // ============================================================
    // payment-service 실패 시나리오
    // ============================================================

    /**
     * payment-service 가 FAILED 를 반환하면 LOAN_185(422) 가 응답되고,
     * loan_execution row 는 STATUS_FAILED + pi_id 로 커밋되어야 한다.
     */
    @Test @Order(40)
    void payment_실패시_LOAN_185_반환_FAILED_row_저장() throws Exception {
        String idemKey = "exec-fail-test";
        // 이 키를 포함하는 X-Idempotency-Key 에 대해서만 FAILED 반환 (priority=1 > 기본 stub)
        PAYMENT_MOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/payments"))
                .atPriority(1)
                .withHeader("X-Idempotency-Key", WireMock.containing("exec-fail-test"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentInstructionId\":\"PI-FAIL-001\"," +
                                  "\"transactionNo\":\"TXN-FAIL\"," +
                                  "\"status\":\"FAILED\"," +
                                  "\"failureCategory\":\"INSUFFICIENT_BALANCE\"}")));

        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_185"));

        LoanExecution failed = executionRepository.findByIdempotencyKey(idemKey).orElseThrow();
        assertThat(failed.getExecStatusCd()).isEqualTo(LoanExecution.STATUS_FAILED);
        assertThat(failed.getPiId()).isEqualTo("PI-FAIL-001");
    }

    /**
     * FAILED 로 기록된 row 에 동일 멱등키로 재호출하면 새 row 없이 기존 FAILED 응답을 반환한다.
     */
    @Test @Order(41)
    void payment_실패후_동일키_재호출_FAILED_응답_반환() throws Exception {
        long countBefore = executionRepository.count();
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "exec-fail-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(9_999_999L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execStatusCd").value("FAILED"))
                .andReturn();

        assertThat(executionRepository.count()).isEqualTo(countBefore);
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "EXEC_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"실행 테스트 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
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
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901", "holderName":"홍길동",
                  "autoDebitYn":true, "debitDay":15 }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private String drawdownBody(long amount) {
        return """
                {
                  "executedAmount":%d,
                  "disbursementBankCd":"088",
                  "disbursementAccountNo":"1109999998888"
                }
                """.formatted(amount);
    }
}
