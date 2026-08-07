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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신용정보 신고 통합 테스트.
 *
 * 시나리오:
 *   10) NEW_LOAN / KCB 신고 → 201 REQUESTED + externalTxNo/reportedAt 은 dispatch 단계까지 null
 *   11) crptId 단건 조회 + JSONB payload round-trip 확인
 *   12) DELINQUENCY / NICE 신고 → 누적
 *   13) 이력 목록 (2건, created_at 오름차순)
 *   14) 미존재 crptId → 404 LOAN_150
 *   15) 미존재 계약 신고 → 404 LOAN_062
 *   16) reportTypeCd 누락 → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrId;
    private Long firstCrptId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    @Test @Order(10)
    void NEW_LOAN_신고_REQUESTED_적재() throws Exception {
        String body = """
                {
                  "reportTypeCd":"NEW_LOAN",
                  "agencyCd":"KCB",
                  "reportTargetCd":"NEW",
                  "reportReasonCd":"DISBURSED",
                  "reportPayload":"{\\"loanType\\":\\"CREDIT\\",\\"amount\\":12000000}"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/credit-info-reports", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.crptTypeCd").value("NEW_LOAN"))
                .andExpect(jsonPath("$.data.crptAgencyCd").value("KCB"))
                // plan 02 step 4: 동기 외부 전송 제거. 신고 row 는 REQUESTED 로만 적재되고
                // 외부 호출은 dispatch 배치(별 plan)에서 수행한다.
                .andExpect(jsonPath("$.data.crptStatusCd").value("REQUESTED"))
                .andExpect(jsonPath("$.data.reportTargetCd").value("NEW"))
                .andExpect(jsonPath("$.data.externalTxNo").doesNotExist())
                .andExpect(jsonPath("$.data.reportedAt").doesNotExist())
                .andReturn();
        JsonNode data = extractData(result);
        firstCrptId = data.get("crptId").asLong();
        assertThat(firstCrptId).isPositive();
    }

    @Test @Order(11)
    void crptId_단건_payload_round_trip() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/credit-info-reports/{crptId}", firstCrptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crptId").value(firstCrptId))
                .andReturn();
        // PostgreSQL JSONB 는 키 정렬·공백 정규화를 수행 — substring 단위로만 검증
        String payload = extractData(result).get("reportPayload").asText();
        assertThat(payload).contains("loanType").contains("CREDIT")
                .contains("amount").contains("12000000");
    }

    @Test @Order(12)
    void DELINQUENCY_신고_누적() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/credit-info-reports", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportTypeCd":"DELINQUENCY",
                                  "agencyCd":"NICE",
                                  "reportTargetCd":"UPDATE",
                                  "reportReasonCd":"OVERDUE_5D"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.crptStatusCd").value("REQUESTED"));
    }

    @Test @Order(13)
    void 이력_목록_계약자동발행_포함_3건() throws Exception {
        // 계약 체결 시 ContractCreditReportListener 가 NEW_LOAN 을 자동발행한다(@Async + AFTER_COMMIT).
        // 따라서 수동 2건(NEW_LOAN, DELINQUENCY) + 자동 1건 = 3건이다.
        // 자동발행은 비동기라 도착 시점이 고정되지 않으므로 조건 대기로 확인한다.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/loan-contracts/{cntrId}/credit-info-reports", cntrId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalCount").value(3)));

        // 적재 순서는 비동기 도착 순서에 좌우되므로 위치가 아니라 구성으로 검증한다.
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/credit-info-reports", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].crptTypeCd")
                        .value(containsInAnyOrder("NEW_LOAN", "NEW_LOAN", "DELINQUENCY")));
    }

    @Test @Order(14)
    void 미존재_crptId_404() throws Exception {
        mockMvc.perform(get("/api/credit-info-reports/{crptId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_150"));
    }

    @Test @Order(15)
    void 미존재_계약_신고_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/credit-info-reports", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reportTypeCd":"NEW_LOAN", "agencyCd":"KCB", "reportTargetCd":"NEW" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(16)
    void reportTypeCd_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/credit-info-reports", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agencyCd":"KCB", "reportTargetCd":"NEW" }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "RPT_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"신고 테스트", "loanTypeCd":"CREDIT",
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
