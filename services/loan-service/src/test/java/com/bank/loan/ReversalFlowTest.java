package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상환 거래 역분개(TYPE_REVERSAL) 통합 테스트.
 *
 * 연도 격리 — cntrStartDate=20250101. 다른 테스트는 2024/2026/2030/2040/2050 사용.
 *
 * 흐름:
 *   세팅) 상품·신청·승인·약정·계좌·drawdown
 *         tx1 = 회차1 SCHEDULED 상환 (역분개 대상)
 *         tx2 = 회차2 PARTIAL 부분상환 (역분개 거절 대상)
 *   10) tx1 SCHEDULED 역분개 → 신규 reversal row, 회차1 PAID→DUE, restoredRschId=회차1 rschId
 *   11) 동일 Idempotency-Key 재호출 → 기존 reversal row 반환 (멱등)
 *   12) 이미 역분개된 tx1 재역분개 → 409 LOAN_097
 *   13) 미존재 rtxId → 404 LOAN_095
 *   14) PARTIAL tx2 역분개 → 422 LOAN_096 (현 단계 미지원)
 *   15) 다른 cntrId path 로 기존 rtxId 접근 → 404 LOAN_095 (cntrId mismatch / 보안)
 *   16) reversal row 자체를 역분개 시도 → 422 LOAN_096 (reversalYn=Y target 차단)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReversalFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final String CNTR_START_DATE = "20250101";
    private static final long   CONTRACTED_AMOUNT = 12_000_000L;
    private static final int    PERIOD_MONTHS     = 12;
    private static final int    RATE_BPS          = 600;

    private Long  cntrId;
    private long  tx1RtxId;          // 회차1 SCHEDULED
    private long  tx2PartialRtxId;   // 회차2 PARTIAL
    private long  rsch1Id;           // 회차1 rschId
    private long  rev1RtxId;         // 10) 생성된 reversal row
    private final String idemKeyRev1 = "rev-tx1-" + UUID.randomUUID();

    @BeforeAll
    void setupContract() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
        triggerDrawdown(cntrId, CONTRACTED_AMOUNT);

        MvcResult r1 = mockMvc.perform(post("/api/loan-contracts/{c}/repayments", cntrId)
                        .header("Idempotency-Key", "rev-init-1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":1, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        tx1RtxId = extractData(r1).get("rtxId").asLong();
        rsch1Id  = extractData(r1).get("rschId").asLong();

        MvcResult r2 = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/partial", cntrId)
                        .header("Idempotency-Key", "rev-init-p2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "installmentNo":2, "amount":50000, "channelCd":"MANUAL" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        tx2PartialRtxId = extractData(r2).get("rtxId").asLong();
    }

    @Test @Order(10)
    void SCHEDULED_역분개_회차_PAID_DUE_롤백() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal",
                        cntrId, tx1RtxId)
                        .header("Idempotency-Key", idemKeyRev1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reversalReasonCd":"MISTAKE", "reversalRemark":"test" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetRtxId").value(tx1RtxId))
                .andExpect(jsonPath("$.data.cntrId").value(cntrId))
                .andExpect(jsonPath("$.data.restoredRschId").value(rsch1Id))
                .andReturn();
        rev1RtxId = extractData(result).get("reversalRtxId").asLong();

        // 회차1 DUE 로 복귀
        mockMvc.perform(get("/api/loan-contracts/{c}/repayment-schedules", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rschStatusCd").value("DUE"));

        // 거래 목록에 reversal row 가 TYPE_REVERSAL 로 존재
        MvcResult list = mockMvc.perform(get("/api/loan-contracts/{c}/repayments", cntrId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = extractData(list).get("items");
        boolean found = false;
        for (JsonNode tx : items) {
            if (tx.get("rtxId").asLong() == rev1RtxId) {
                assertThat(tx.get("rtxTypeCd").asText()).isEqualTo("REVERSAL");
                assertThat(tx.get("rtxStatusCd").asText()).isEqualTo("SUCCESS");
                assertThat(tx.get("totalAmount").asLong()).isPositive();
                found = true;
                break;
            }
        }
        assertThat(found).as("reversal row rtxId=%d not found in list", rev1RtxId).isTrue();
    }

    @Test @Order(11)
    void 멱등성_동일키_재호출() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal",
                        cntrId, tx1RtxId)
                        .header("Idempotency-Key", idemKeyRev1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reversalReasonCd":"MISTAKE" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(extractData(result).get("reversalRtxId").asLong()).isEqualTo(rev1RtxId);
    }

    @Test @Order(12)
    void 이미_역분개된_tx_재역분개_409() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal", cntrId, tx1RtxId)
                        .header("Idempotency-Key", "rev-dup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_097"));
    }

    @Test @Order(13)
    void 미존재_rtxId_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal", cntrId, 999_999_999L)
                        .header("Idempotency-Key", "rev-nf-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_095"));
    }

    @Test @Order(14)
    void PARTIAL_역분개_미지원_422() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal", cntrId, tx2PartialRtxId)
                        .header("Idempotency-Key", "rev-partial-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_096"));
    }

    @Test @Order(15)
    void 다른_cntrId_path_접근_404() throws Exception {
        // 존재하는 rtxId 지만 path 의 cntrId 가 다름 → 보안 차단 (LOAN_095)
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal",
                        999_999_999L, tx2PartialRtxId)
                        .header("Idempotency-Key", "rev-mismatch-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_095"));
    }

    @Test @Order(16)
    void reversal_row_자체_역분개_422() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{c}/repayments/{r}/reversal", cntrId, rev1RtxId)
                        .header("Idempotency-Key", "rev-self-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_096"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "REV_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"역분개 테스트 상품", "loanTypeCd":"CREDIT",
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
                        .header("Idempotency-Key", "rev-drawdown-" + UUID.randomUUID())
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
