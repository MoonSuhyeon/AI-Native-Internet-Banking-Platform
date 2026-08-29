package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
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

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역분개 환급 이체 통합 테스트 — piId 있는 거래(자동이체) 역분개 시 payment-service 호출 검증.
 *
 * 연도 격리 — cntrStartDate=20320201. 다른 배치 테스트 연도(2024/2025/2026/2030/2033/2035/2036/2040/2050/2060)와 겹치지 않음.
 *
 * 흐름:
 *   세팅) 계약(autoDebitYn=Y) → drawdown → 자동이체 배치 ×2
 *         → tx1(1회차, piId=PI-TEST-001) / tx2(2회차, piId=PI-TEST-001) 생성
 *   10) tx1 역분개 → payment-service COMPLETED → 201 성공, reversalRtxId 반환
 *   11) tx2 역분개 → payment-service FAILED → 422 LOAN_186, DB 롤백(tx2 여전히 SUCCESS/reversalYn=N)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReversalRefundFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    @Autowired
    private RepaymentTransactionRepository txRepository;

    private static final String CNTR_START_DATE = "20320201";
    private static final String DUE_DATE_M1     = "20320302";  // 20320301 삼일절 → 휴일 보정
    private static final String DUE_DATE_M2     = "20320401";  // 2032-04-01 (목)
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 600;

    private Long cntrId;
    private long tx1RtxId;
    private long tx2RtxId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", DUE_DATE_M1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));

        mockMvc.perform(post("/api/internal/auto-debit/run").param("baseDate", DUE_DATE_M2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));

        MvcResult list = mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(list).get("items");
        long first = -1, second = -1;
        for (JsonNode tx : items) {
            if ("SCHEDULED".equals(tx.get("rtxTypeCd").asText())
                    && "SUCCESS".equals(tx.get("rtxStatusCd").asText())) {
                if (first == -1) first = tx.get("rtxId").asLong();
                else second = tx.get("rtxId").asLong();
            }
        }
        assertThat(first).as("1회차 SCHEDULED tx 없음").isPositive();
        assertThat(second).as("2회차 SCHEDULED tx 없음").isPositive();
        tx1RtxId = Math.min(first, second);
        tx2RtxId = Math.max(first, second);
    }

    @Test @Order(10)
    void 자동이체_역분개_환급이체_COMPLETED_성공() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal",
                        cntrId, tx1RtxId)
                        .header("Idempotency-Key", "rrf-ok-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reversalReasonCd":"MISTAKE" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetRtxId").value(tx1RtxId))
                .andExpect(jsonPath("$.data.cntrId").value(cntrId))
                .andReturn();

        long reversalRtxId = extractData(result).get("reversalRtxId").asLong();
        assertThat(reversalRtxId).isPositive();
    }

    @Test @Order(11)
    void 자동이체_역분개_환급이체_FAILED_422_LOAN186() throws Exception {
        // 환급 멱등키는 호출자 키가 아니라 rtxId 로 결정적으로 구성된다(REV-{cntrId}-{rtxId})
        PAYMENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/v1/internal/payments"))
                .withHeader("X-Idempotency-Key", containing("REV-" + cntrId + "-" + tx2RtxId))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentInstructionId\":\"PI-FAIL-REFUND-001\"," +
                                  "\"transactionNo\":\"TXN-FAIL-REFUND-001\"," +
                                  "\"status\":\"FAILED\"," +
                                  "\"failureCategory\":\"INSUFFICIENT_FUNDS\"}")));

        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal",
                        cntrId, tx2RtxId)
                        .header("Idempotency-Key", "rev-fail-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reversalReasonCd":"MISTAKE" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_186"));

        // DB 롤백 확인 — tx2 는 여전히 SUCCESS / reversalYn=N
        var tx2 = txRepository.findByRtxIdAndDeletedAtIsNull(tx2RtxId).orElseThrow();
        assertThat(tx2.getReversalYn()).isFalse();
        assertThat(tx2.getRtxStatusCd()).isEqualTo("SUCCESS");
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    private Long createProduct() throws Exception {
        String code = "RRF_" + uniq();
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "prodCd":"%s", "prodName":"역분개환급 테스트 상품", "loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                                  "baseRateBps":600,
                                  "minAmount":1000000, "maxAmount":100000000,
                                  "minPeriodMo":12, "maxPeriodMo":60,
                                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                                }
                                """.formatted(code)))
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
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                                  "requestedAmount":%d, "requestedPeriodMo":%d,
                                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS)))
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
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "applId":%d,
                                  "contractedAmount":%d,
                                  "contractedPeriodMo":%d,
                                  "baseRateBps":%d,
                                  "rateTypeCd":"FIXED",
                                  "repaymentMethodCd":"EQUAL",
                                  "cntrStartDate":"%s"
                                }
                                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS, CNTR_START_DATE)))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "bankCd":"088", "accountNo":"1102345678901",
                                  "holderName":"홍길동", "autoDebitYn":true, "debitDay":1 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "rrf-drawdown-" + UUID.randomUUID())
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
