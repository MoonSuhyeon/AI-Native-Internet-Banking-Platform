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
 * 증명서 발급 통합 테스트.
 *
 * 시나리오:
 *   10) BALANCE 발급 → 201 ISSUED, cert_no 채번
 *   11) certId 단건 조회 OK
 *   12) DEBT 재발급 → 201, 다른 cert_no
 *   13) 목록 조회 (2건, issued_at 오름차순)
 *   14) 미존재 certId 단건 → 404 LOAN_140
 *   15) 미존재 계약 발급 → 404 LOAN_062
 *   16) certTypeCd 누락 → 400 (validation)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanCertificateFlowTest extends AbstractLoanIntegrationTest {

    @Autowired
    private LoanApplicationRepository applicationRepository;

    private static final long CONTRACTED_AMOUNT = 12_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

    private Long cntrId;
    private Long firstCertId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    @Test @Order(10)
    void BALANCE_증명서_발급() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/certificates", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "certTypeCd":"BALANCE",
                                  "certPurposeCd":"TAX",
                                  "issueChannelCd":"MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.certTypeCd").value("BALANCE"))
                .andExpect(jsonPath("$.data.certStatusCd").value("ISSUED"))
                .andExpect(jsonPath("$.data.certPurposeCd").value("TAX"))
                .andExpect(jsonPath("$.data.issueChannelCd").value("MOBILE"))
                .andExpect(jsonPath("$.data.certNo").exists())
                .andExpect(jsonPath("$.data.issuedAt").exists())
                .andReturn();
        JsonNode data = extractData(result);
        firstCertId = data.get("certId").asLong();
        String certNo = data.get("certNo").asText();
        assertThat(firstCertId).isPositive();
        assertThat(certNo).startsWith("CERT-").hasSize(22);
    }

    @Test @Order(11)
    void certId_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-certificates/{certId}", firstCertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.certId").value(firstCertId))
                .andExpect(jsonPath("$.data.certTypeCd").value("BALANCE"));
    }

    @Test @Order(12)
    void DEBT_재발급_새cert_no() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-contracts/{cntrId}/certificates", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "certTypeCd":"DEBT", "issueChannelCd":"EMAIL" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.certTypeCd").value("DEBT"))
                .andReturn();
        long secondCertId = extractData(result).get("certId").asLong();
        assertThat(secondCertId).isNotEqualTo(firstCertId);
    }

    @Test @Order(13)
    void 발급_이력_2건() throws Exception {
        mockMvc.perform(get("/api/loan-contracts/{cntrId}/certificates", cntrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].certTypeCd").value("BALANCE"))
                .andExpect(jsonPath("$.data.items[1].certTypeCd").value("DEBT"));
    }

    @Test @Order(14)
    void 미존재_certId_404() throws Exception {
        mockMvc.perform(get("/api/loan-certificates/{certId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_140"));
    }

    @Test @Order(15)
    void 미존재_계약_발급_404() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/certificates", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "certTypeCd":"BALANCE" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_062"));
    }

    @Test @Order(16)
    void certTypeCd_누락_400() throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/certificates", cntrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "certPurposeCd":"TAX" }
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
        String code = "CERT_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"증명서 테스트", "loanTypeCd":"CREDIT",
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
