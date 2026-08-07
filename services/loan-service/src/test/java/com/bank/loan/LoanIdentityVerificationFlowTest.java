package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본인확인(IDV) 통합 테스트. 현재 외부 인증 stub — 항상 PASS.
 *
 * 시나리오:
 *   10) PASS — 적재 결과(상태/결과/마스킹된 휴대폰/CI 해시/외부 거래번호) 검증
 *   11) GET 단건 조회 OK
 *   12) 중복 호출 허용 — 같은 신청에 IDV 2회 호출 모두 201 (현재 정책상 unique 강제 없음)
 *   13) POST 미존재 applId → 404 LOAN_012
 *   14) GET 미존재 idvId → 404 LOAN_021
 *   15) mobileNo 형식 오류(9자리) → 400
 *   16) 필수 필드(idvMethodCd) 누락 → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanIdentityVerificationFlowTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT  = 10_000_000L;
    private static final int  MONTHS  = 24;
    private static final int  BASE_BPS = 500;

    private Long prodId;
    private Long applId;
    private Long idvId;  // 시나리오 10 에서 채움 — 시나리오 11 GET 에 사용

    @org.junit.jupiter.api.BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        applId = createApplication(prodId, 15001);
    }

    @Test @Order(10)
    void PASS_적재_결과_검증() throws Exception {
        String body = """
                {
                  "idvMethodCd":"PASS_APP",
                  "idvTargetCd":"BORROWER",
                  "mobileNo":"01012345678"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applId").value(applId))
                .andExpect(jsonPath("$.data.idvStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.idvResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.idvMethodCd").value("PASS_APP"))
                .andExpect(jsonPath("$.data.idvTargetCd").value("BORROWER"))
                // 평문 mobileNo 는 응답에 노출되지 않고 masked 만 — Masking.mobile 결과 (예: 010-****-5678)
                .andExpect(jsonPath("$.data.mobileNoMasked").exists())
                .andExpect(jsonPath("$.data.ciHash").exists())
                .andExpect(jsonPath("$.data.externalTxNo").exists())
                .andExpect(jsonPath("$.data.verifiedAt").exists())
                .andReturn();
        idvId = extractData(result).get("idvId").asLong();
    }

    @Test @Order(11)
    void GET_단건_조회_OK() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/identity-verifications/{idvId}", applId, idvId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idvId").value(idvId))
                .andExpect(jsonPath("$.data.idvResultCd").value("PASS"));
    }

    @Test @Order(12)
    void 중복_호출_허용() throws Exception {
        // 현재 정책상 unique 제약 없음 — 두 번째 호출도 새 idv row 생성 (201)
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test @Order(13)
    void POST_미존재_applId_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test @Order(14)
    void GET_미존재_idvId_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/identity-verifications/{idvId}",
                        applId, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_021"));
    }

    @Test @Order(15)
    void mobileNo_형식오류_400() throws Exception {
        // 9자리 → @Pattern(\d{10,11}) 위반
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"010123456"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(16)
    void 필수필드_idvMethodCd_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678"
                                }
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
        String code = "IDV_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"IDV 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(code, BASE_BPS);
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

    private Long createApplication(Long prodId, long customerId) throws Exception {
        String body = """
                {
                  "customerId":%d, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(customerId, prodId, AMOUNT, MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }
}
