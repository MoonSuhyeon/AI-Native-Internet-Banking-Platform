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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 온라인(WEB/MOBILE) 상환 통합 테스트 — POST /repayments/online.
 *
 * 연도 격리 — cntrStartDate=20270201 (2027-03-01 월요일 = 1회차 due_date).
 * 다른 배치 테스트 연도(2024/2025/2026/2030/2032/2033/2035/2036/2040/2050/2060)와 겹치지 않음.
 *
 * 시나리오:
 *   10) 1회차 온라인 상환 → payment COMPLETED → 201, channelCd=WEB, piId 설정
 *   11) 동일 Idempotency-Key 재호출 → 기존 tx 반환 (멱등)
 *   12) 이미 PAID 된 회차 재시도(새 키) → 409/422 LOAN_091
 *   13) payment FAILED → 422 LOAN_187, DB 에 FAILED tx 기록
 *   14) 창구 수납(POST /repayments) 은 payment-service 미호출 확인 → 정상 201
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnlineRepaymentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    @Autowired
    private RepaymentTransactionRepository txRepository;

    private static final String CNTR_START_DATE = "20270201";  // 1회차 due: 20270301(월)
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 600;

    private Long cntrId;
    private long rsch2Id;   // 2회차 rschId — 13) FAILED 테스트용
    private final String idemKeyOk = "onl-ok-" + UUID.randomUUID();

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        // 2회차 rschId 수집 (13번 테스트 FAILED 경로에서 사용)
        MvcResult schedules = mockMvc.perform(
                        get("/api/loan-contracts/{c}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(schedules).get("items");
        rsch2Id = items.get(1).get("rschId").asLong();  // 2회차
    }

    @Test @Order(10)
    void 온라인_상환_COMPLETED_성공() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/online", cntrId)
                        .header("Idempotency-Key", idemKeyOk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"WEB" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rtxStatusCd").value("SUCCESS"))
                .andExpect(jsonPath("$.data.channelCd").value("WEB"))
                .andReturn();

        // piId 설정 확인 (WireMock 기본 stub 반환값).
        // RepaymentTransactionResponse 는 piId 를 노출하지 않으므로(V24 로 DB 컬럼만 추가됨)
        // 적재된 엔티티에서 확인한다.
        Long rtxId = extractData(result).get("rtxId").asLong();
        assertThat(txRepository.findById(rtxId).orElseThrow().getPiId())
                .isEqualTo("PI-TEST-001");

        // 회차1 PAID 전이 확인
        mockMvc.perform(get("/api/loan-contracts/{c}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("PAID"));
    }

    @Test @Order(11)
    void 멱등성_동일키_재호출() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/online", cntrId)
                        .header("Idempotency-Key", idemKeyOk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"WEB" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(extractData(result).get("rtxStatusCd").asText()).isEqualTo("SUCCESS");
        // 거래 목록 1건 유지
        mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test @Order(12)
    void 이미_PAID_회차_재시도_409() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/online", cntrId)
                        .header("Idempotency-Key", "onl-dup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"WEB" }
                                """))
                // LOAN_091 은 CONFLICT(409) 로 정의돼 있다. "이미 납부됨"은 상태 충돌이므로
                // 409 가 맞고, 422 를 기대하던 쪽이 낡았다.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_091"));
    }

    @Test @Order(13)
    void payment_FAILED_422_LOAN187_FAILED_tx_기록() throws Exception {
        // 2회차 상환 시 FAILED 응답 스텁
        PAYMENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/v1/payments"))
                .withHeader("X-Idempotency-Key", containing("onl-fail"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentInstructionId\":\"PI-FAIL-ONL-001\"," +
                                  "\"transactionNo\":\"TXN-FAIL-ONL-001\"," +
                                  "\"status\":\"FAILED\"," +
                                  "\"failureCategory\":\"INSUFFICIENT_FUNDS\"}")));

        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/online", cntrId)
                        .header("Idempotency-Key", "onl-fail-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":2, "channelCd":"MOBILE" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_187"));

        // FAILED tx 가 DB 에 기록됐는지 확인 (2회차는 여전히 DUE)
        mockMvc.perform(get("/api/loan-contracts/{c}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].rschStatusCd").value("DUE"));

        mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));  // 1번(SUCCESS) + 2번(FAILED)
    }

    @Test @Order(14)
    void 창구수납_payment_미연동() throws Exception {
        // 2회차는 test13 에서 FAILED(DUE 유지) — 창구 수납으로 정상 상환
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments", cntrId)
                        .header("Idempotency-Key", "manual-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":2, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.channelCd").value("MANUAL"))
                .andExpect(jsonPath("$.data.rtxStatusCd").value("SUCCESS"));

        // payment-service 가 이 요청으로 인해 호출되지 않았는지: 창구 수납 idempotencyKey 로 piId=null 확인
        // (WireMock 미검증 — channelCd=MANUAL + SUCCESS 가 핵심 검증)
        PAYMENT_MOCK.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments"))
                .withHeader("X-Idempotency-Key", containing("manual-")));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    private Long createProduct() throws Exception {
        String code = "ONL_" + uniq();
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "prodCd":"%s", "prodName":"온라인상환 테스트 상품", "loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                                  "baseRateBps":600,
                                  "minAmount":1000000, "maxAmount":100000000,
                                  "minPeriodMo":12, "maxPeriodMo":60,
                                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
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
                                  "holderName":"홍길동", "autoDebitYn":"N", "debitDay":1 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void triggerDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "onl-drawdown-" + UUID.randomUUID())
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
