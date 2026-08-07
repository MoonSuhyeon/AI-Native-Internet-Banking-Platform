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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보증 취소 시 약정·실행 영향 회귀 테스트.
 *
 * 시나리오:
 *   10) 약정 전 SIGNED 보증인 취소 후 약정 불가 (422 LOAN_175)
 *   20) 약정 후 SIGNED 보증인 취소 후 drawdown 불가 (422 LOAN_176)
 *   21) 취소 후 새 보증인 등록·서명으로 약정·drawdown 복구 가능
 *       (CONTRACTED 이후 보증인 추가가 차단되므로 약정 전 취소 경로 preCancelApplId 에서 복구 검증)
 *
 * 전제:
 *   - 보증 필수 상품 (guarantorRequiredYn=Y, minGuarantorCount=1)
 *   - application.APPROVED 는 repository 직접 조작으로 전이 (본심사 bypass)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuarantorCancelImpactTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long AMOUNT  = 10_000_000L;
    private static final int  MONTHS  = 24;

    private Long prodId;

    // 10·21번 시나리오: 약정 전 취소 → 약정 불가 → 새 보증인으로 복구
    private Long preCancelApplId;
    private Long preCancelGagrId;

    // 20번 시나리오: 약정 후 취소 → drawdown 불가
    private Long postCancelApplId;
    private Long postCancelGagrId;
    private Long postCancelCntrId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createGuarantorProduct();
        activateProduct(prodId);

        // --- 약정 전 취소 시나리오 ---
        preCancelApplId = createApplication(prodId);
        forceApprove(preCancelApplId);
        preCancelGagrId = registerAndSignGuarantor(preCancelApplId, "01022210001");

        // --- 약정 후 취소 시나리오 ---
        postCancelApplId = createApplication(prodId);
        forceApprove(postCancelApplId);
        postCancelGagrId = registerAndSignGuarantor(postCancelApplId, "01022210002");
        postCancelCntrId = createContract(postCancelApplId);
        registerAndVerifyRepaymentAccount(postCancelCntrId);
    }

    // ============================================================
    // 10번: 약정 전 보증인 취소 → 약정 불가
    // ============================================================

    @Test @Order(10)
    void 약정_전_SIGNED_취소_후_약정_불가_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/cancel",
                        preCancelApplId, preCancelGagrId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gagrStatusCd").value("CANCELED"));

        // 취소 후 약정 체결 시도 — SIGNED 수 0 < 1 → LOAN_175
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(preCancelApplId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_175"));
    }

    // ============================================================
    // 20번: 약정 후 보증인 취소 → drawdown 불가
    // ============================================================

    @Test @Order(20)
    void 약정_후_SIGNED_취소_후_drawdown_불가_422() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/cancel",
                        postCancelApplId, postCancelGagrId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gagrStatusCd").value("CANCELED"));

        // 취소 후 drawdown 시도 — SIGNED 수 0 < 1 → LOAN_176
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", postCancelCntrId)
                        .header("Idempotency-Key", "exec-cancel-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(5_000_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_176"));
    }

    // ============================================================
    // 21번: 새 보증인 서명 후 약정·drawdown 복구
    //   preCancelApplId 는 test 10 에서 약정 실패 → 여전히 APPROVED
    //   → 새 보증인 등록 가능 (APPROVED 는 REGISTERABLE_APPL_STATUSES 포함)
    // ============================================================

    @Test @Order(21)
    void 새_보증인_서명_후_약정_및_drawdown_복구() throws Exception {
        // 새 보증인 등록 + 서명 (preCancelApplId 는 APPROVED, 약정 미체결 상태)
        registerAndSignGuarantor(preCancelApplId, "01022210003");

        // 약정 체결 — SIGNED 수 1 >= 1 → 성공
        Long cntrId = createContract(preCancelApplId);
        registerAndVerifyRepaymentAccount(cntrId);

        // drawdown → 201 OK
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "exec-recovery-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drawdownBody(5_000_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.execStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.executedAmount").value(5_000_000L));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createGuarantorProduct() throws Exception {
        String code = "GCNL_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"보증 취소 영향 테스트 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":1000000, "maxAmount":50000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false,
                  "guarantorRequiredYn":true,
                  "minGuarantorCount":1
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
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":9201, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, AMOUNT, MONTHS);
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

    private Long registerAndSignGuarantor(Long applId, String mobileNo) throws Exception {
        String regBody = """
                {
                  "guarantorName":"보증인",
                  "guarantorMobileNo":"%s",
                  "relationTypeCd":"FAMILY",
                  "gagrTypeCd":"JOINT",
                  "guaranteeAmount":10000000
                }
                """.formatted(mobileNo);
        MvcResult regResult = mockMvc.perform(
                        post("/api/loan-applications/{applId}/guarantor-agreements", applId)
                                .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isCreated())
                .andReturn();
        Long gagrId = extractData(regResult).get("gagrId").asLong();

        String signBody = """
                {
                  "signedDocUrl":"https://docs.bank.com/g%d.pdf",
                  "signedDocHash":"hash%d"
                }
                """.formatted(gagrId, gagrId);
        mockMvc.perform(post("/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/sign",
                        applId, gagrId)
                        .contentType(MediaType.APPLICATION_JSON).content(signBody))
                .andExpect(status().isOk());

        return gagrId;
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
                """.formatted(applId, AMOUNT, MONTHS);
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
                                { "bankCd":"088", "accountNo":"1102345679002",
                                  "holderName":"홍길동", "autoDebitYn":true, "debitDay":15 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private String contractBody(Long applId) {
        return """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, AMOUNT, MONTHS);
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
