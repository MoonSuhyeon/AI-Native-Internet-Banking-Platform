package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 도메인별 negative case 모음. 각 테스트는 순서·상태 의존 없이 독립 수행된다.
 * @BeforeAll 에서 한 번만 ACTIVE 상품 + 활성 신청을 시드한다.
 */
class LoanNegativeCaseTest extends AbstractLoanIntegrationTest {

    private Long activeProdId;     // prodStatusCd = ACTIVE
    private Long draftProdId;      // prodStatusCd = DRAFT (비활성)
    private Long activeApplId;     // applStatusCd = SUBMITTED, prod = activeProd

    @BeforeAll
    void seed() throws Exception {
        activeProdId = createProduct("ACTIVE");
        activate(activeProdId);
        draftProdId = createProduct("DRAFT");
        activeApplId = createApplication(activeProdId);
    }

    // ============================================================
    // 상품
    // ============================================================

    @Test
    void 상품_등록_중복_prodCd_409() throws Exception {
        String code = "NEG_DUP_" + uniq();
        createProductWithCode(code);
        String body = productBody(code, 1_000_000L, 10_000_000L, 12, 60);

        mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_001"));
    }

    @Test
    void 상품_단건_조회_미존재_404() throws Exception {
        mockMvc.perform(get("/api/loan-products/{prodId}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_002"));
    }

    @Test
    void 상품_등록_금액_역전_400() throws Exception {
        String body = productBody("NEG_AMT_" + uniq(), 10_000_000L, 1_000_000L, 12, 60);
        mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_003"));
    }

    @Test
    void 상품_등록_기간_역전_400() throws Exception {
        String body = productBody("NEG_PRD_" + uniq(), 1_000_000L, 10_000_000L, 60, 12);
        mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_003"));
    }

    @Test
    void 상품_단종_미존재_404() throws Exception {
        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_002"));
    }

    @Test
    void 상품_등록_검증실패_envelope_구조() throws Exception {
        // prodCd 누락 → @NotBlank 위반
        String body = """
                {
                  "prodName":"무효", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450, "minAmount":1000000, "maxAmount":10000000,
                  "minPeriodMo":12, "maxPeriodMo":60
                }
                """;
        mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_400"))
                .andExpect(jsonPath("$.data.errors[0].field").value("prodCd"));
    }

    // ============================================================
    // 우대금리
    // ============================================================

    @Test
    void 우대금리_상품_미존재_404() throws Exception {
        mockMvc.perform(post("/api/loan-products/{prodId}/preferential-rate-policies", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "policyName":"무효", "conditionCd":"SALARY_TRANSFER", "preferentialRateBps":30 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_002"));
    }

    @Test
    void 우대금리_동일조건_중복_409() throws Exception {
        Long prodId = createProduct("ACTIVE");
        activate(prodId);
        String body = """
                { "policyName":"우대1", "conditionCd":"AUTO_DEBIT_NEG", "preferentialRateBps":30 }
                """;
        mockMvc.perform(post("/api/loan-products/{prodId}/preferential-rate-policies", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-products/{prodId}/preferential-rate-policies", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_005"));
    }

    // ============================================================
    // 신청
    // ============================================================

    @Test
    void 신청_비활성_상품_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(draftProdId, 5_000_000, 24)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_010"));
    }

    @Test
    void 신청_금액_범위_초과_400() throws Exception {
        // activeProdId 의 maxAmount=10_000_000 으로 시드됨
        mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(activeProdId, 1_000_000_000L, 24)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_011"));
    }

    @Test
    void 신청_기간_범위_초과_400() throws Exception {
        mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(activeProdId, 5_000_000, 999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_011"));
    }

    @Test
    void 신청_상품_미존재_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(999_999L, 5_000_000, 24)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_002"));
    }

    @Test
    void 신청_취소_미존재_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/cancel", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cancelReasonCd":"CUST_REQ" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    // ============================================================
    // IDV / 동의
    // ============================================================

    @Test
    void IDV_잘못된_mobileNo_400() throws Exception {
        // mobileNo Pattern("\\d{10,11}") 위반
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", activeApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS", "idvTargetCd":"APPLICANT", "mobileNo":"123" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_400"))
                .andExpect(jsonPath("$.data.errors[0].field").value("mobileNo"));
    }

    @Test
    void IDV_미존재_신청_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS", "idvTargetCd":"APPLICANT", "mobileNo":"01012345678" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test
    void IDV_상태조회_미존재_404() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/identity-verifications/{idvId}",
                        activeApplId, 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_021"));
    }

    @Test
    void 동의_미존재_신청_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-consents", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "consentTypeCd":"CB", "consentScopeCd":"FULL", "consentTargetCd":"KCB" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    // ============================================================
    // 서류
    // ============================================================

    @Test
    void 서류_업로드_미존재_신청_404() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.pdf", MediaType.APPLICATION_PDF_VALUE, "x".getBytes());
        mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", 999_999L)
                        .file(file)
                        .param("docTypeCd", "INCOME_PROOF"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test
    void 서류_업로드_빈파일_400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);
        mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", activeApplId)
                        .file(empty)
                        .param("docTypeCd", "INCOME_PROOF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_040"));
    }

    @Test
    void 서류_다운로드_미존재_404() throws Exception {
        mockMvc.perform(get("/api/loan-documents/{docId}/download", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_041"));
    }

    @Test
    void 서류_삭제_미존재_404() throws Exception {
        mockMvc.perform(delete("/api/loan-documents/{docId}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_041"));
    }

    // ============================================================
    // 담보
    // ============================================================

    @Test
    void 담보_등록_미존재_신청_404() throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "colTypeCd":"REAL_ESTATE", "declaredValue":100000000 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_012"));
    }

    @Test
    void 담보_수정_미존재_404() throws Exception {
        mockMvc.perform(patch("/api/collaterals/{colId}", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "declaredValue":100 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_050"));
    }

    @Test
    void 담보_감정평가_미존재_404() throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/evaluations", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "evalMethodCd":"APPRAISAL", "appraisedValue":100 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_050"));
    }

    @Test
    void 담보_해제_미존재_404() throws Exception {
        mockMvc.perform(post("/api/collaterals/{colId}/release", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "releaseReasonCd":"LOAN_PAID" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_050"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String productBody(String prodCd, long minAmount, long maxAmount, int minPeriod, int maxPeriod) {
        return """
                {
                  "prodCd":"%s", "prodName":"테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":%d, "maxAmount":%d,
                  "minPeriodMo":%d, "maxPeriodMo":%d,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(prodCd, minAmount, maxAmount, minPeriod, maxPeriod);
    }

    private String applicationBody(Long prodId, long amount, int periodMo) {
        return """
                {
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, amount, periodMo);
    }

    private Long createProduct(String label) throws Exception {
        return createProductWithCode(label + "_" + uniq());
    }

    private Long createProductWithCode(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(code, 1_000_000, 10_000_000, 12, 60)))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activate(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prodStatusCd":"ACTIVE" }
                                """))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prodId, 5_000_000, 24)))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }
}
