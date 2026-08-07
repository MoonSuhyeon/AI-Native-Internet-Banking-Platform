package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
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
 * 상환계좌(repayment_account) 등록·검증 + drawdown 사전조건 통합 테스트.
 *
 * 흐름:
 *   1)  상품 등록 → ACTIVE → 신청 → repository 로 APPROVED 강제 전이 (본심사 API 미구현)
 *   2)  약정 생성 (cntrId 확보)
 *   10) 미등록 상태 drawdown   → 422 LOAN_080
 *   11) 상환계좌 등록            → 201 REGISTERED
 *   12) 중복 등록                → 409 LOAN_081
 *   13) 미검증 drawdown          → 422 LOAN_083
 *   14) 상환계좌 검증            → 200 VERIFIED
 *   15) VERIFIED 재검증          → 422 LOAN_082
 *   16) 정상 drawdown            → 201 DONE
 *   17) 상환계좌 조회            → 200
 *   18) 미존재 cntrId 조회       → 404 LOAN_080
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepaymentAccountFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private Long prodId;
    private Long applId;
    private Long cntrId;

    @BeforeAll
    void setupContract() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    // ============================================================
    // drawdown 사전조건 — 상환계좌 없음
    // ============================================================

    @Test @Order(10)
    void 미등록_상환계좌_drawdown_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "rae-drawdown-pre-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_080"));
    }

    // ============================================================
    // 등록
    // ============================================================

    @Test @Order(11)
    void 상환계좌_등록() throws Exception {
        String body = """
                {
                  "bankCd":"088",
                  "accountNo":"1102345678901",
                  "holderName":"홍길동",
                  "autoDebitYn":true,
                  "debitDay":15
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.racctStatusCd").value("REGISTERED"))
                .andExpect(jsonPath("$.data.bankCd").value("088"))
                .andExpect(jsonPath("$.data.accountNoMasked").value("110-****-8901"))
                .andExpect(jsonPath("$.data.holderNameMasked").value("홍*동"))
                .andExpect(jsonPath("$.data.autoDebitYn").value(true))
                .andExpect(jsonPath("$.data.debitDay").value(15))
                .andExpect(jsonPath("$.data.verifiedAt").doesNotExist())
                .andReturn();
        long racctId = extractData(result).get("racctId").asLong();
        assertThat(racctId).isPositive();
    }

    @Test @Order(12)
    void 상환계좌_중복_등록_차단() throws Exception {
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901", "holderName":"홍길동" }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_081"));
    }

    // ============================================================
    // drawdown — 미검증 차단
    // ============================================================

    @Test @Order(13)
    void 미검증_상환계좌_drawdown_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "rae-drawdown-unverified-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_083"));
    }

    // ============================================================
    // 검증
    // ============================================================

    @Test @Order(14)
    void 상환계좌_검증() throws Exception {
        String body = """
                { "verifyChannelCd":"OPENBANK", "verifyRemark":"실명조회 성공" }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.racctStatusCd").value("VERIFIED"))
                .andExpect(jsonPath("$.data.verifiedAt").exists());
    }

    @Test @Order(15)
    void 이미_검증된_상환계좌_재검증_차단() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_082"));
    }

    // ============================================================
    // drawdown — 정상 통과
    // ============================================================

    @Test @Order(16)
    void 검증_후_drawdown_성공() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "rae-drawdown-ok-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(1_000_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.executedAmount").value(1_000_000))
                .andExpect(jsonPath("$.data.journalEntryNo").exists());
    }

    // ============================================================
    // 조회
    // ============================================================

    @Test @Order(17)
    void 상환계좌_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-account", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntrId").value(cntrId))
                .andExpect(jsonPath("$.data.racctStatusCd").value("VERIFIED"));
    }

    @Test @Order(18)
    void 상환계좌_조회_미존재_404() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/repayment-account", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_080"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "RAE_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"상환계좌 테스트 상품", "loanTypeCd":"CREDIT",
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
                  "requestedAmount":10000000, "requestedPeriodMo":24,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    /**
     * 본심사 API 미구현 — repository 로 강제 APPROVED 전이.
     * 본심사 도입 시 해당 API 호출 체인으로 교체.
     */
    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private Long createContract(Long applId) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":10000000,
                  "contractedPeriodMo":24,
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
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
