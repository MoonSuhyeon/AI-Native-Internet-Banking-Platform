package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LON 도메인 happy-path E2E.
 *
 * 흐름:
 *   1) 상품 등록 → 단건 조회 → 목록 → 수정(ACTIVE) → 우대금리 정책 등록
 *   2) 대출 신청 → 본인확인(IDV) → IDV 조회 → 신용조회 동의
 *   3) 서류 업로드 → 목록 → 다운로드 → 삭제
 *   4) 담보 등록 → 목록 → 수정 → 감정평가 → 해제
 *   5) 신청 취소
 *   6) 상품 단종
 *
 * 한 클래스 안에서 상태가 단계별로 흐른다 (@TestInstance(PER_CLASS)).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanFlowE2ETest extends AbstractLoanIntegrationTest {

    private Long prodId;
    private Long policyId;
    private Long applId;
    private Long idvId;
    private Long csntId;
    private Long docId;
    private Long colId;
    private Long cevalColId;

    // ============================================================
    // 1) 상품
    // ============================================================

    @Test @Order(1)
    void 상품_등록() throws Exception {
        String body = """
                {
                  "prodCd":"E2E_001",
                  "prodName":"E2E 신용대출",
                  "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL",
                  "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":1000000,
                  "maxAmount":100000000,
                  "minPeriodMo":12,
                  "maxPeriodMo":60,
                  "collateralRequiredYn":false,
                  "guarantorRequiredYn":false
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.prodStatusCd").value("DRAFT"))
                .andReturn();
        prodId = extractData(result).get("prodId").asLong();
        assertThat(prodId).isPositive();
    }

    @Test @Order(2)
    void 상품_단건_조회() throws Exception {
        mockMvc.perform(get("/api/loan-products/{prodId}", prodId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prodCd").value("E2E_001"))
                .andExpect(jsonPath("$.data.baseRateBps").value(450));
    }

    @Test @Order(3)
    void 상품_목록() throws Exception {
        // 다른 테스트 클래스가 같은 DB 를 공유하므로 totalCount 절대값 대신
        // 우리가 등록한 prodCd 가 결과에 포함되는지 확인한다.
        mockMvc.perform(get("/api/loan-products")
                        .param("loanTypeCd", "CREDIT")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.prodCd == 'E2E_001')]").exists());
    }

    @Test @Order(4)
    void 상품_수정_및_ACTIVE_전환() throws Exception {
        String body = """
                { "baseRateBps":400, "maxAmount":200000000, "prodStatusCd":"ACTIVE" }
                """;
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseRateBps").value(400))
                .andExpect(jsonPath("$.data.maxAmount").value(200000000))
                .andExpect(jsonPath("$.data.prodStatusCd").value("ACTIVE"));
    }

    @Test @Order(5)
    void 우대금리_정책_등록() throws Exception {
        String body = """
                {
                  "policyName":"급여이체우대",
                  "conditionCd":"SALARY_TRANSFER",
                  "preferentialRateBps":30,
                  "maxStackBps":150
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-products/{prodId}/preferential-rate-policies", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activeYn").value(true))
                .andExpect(jsonPath("$.data.preferentialRateBps").value(30))
                .andReturn();
        policyId = extractData(result).get("policyId").asLong();
        assertThat(policyId).isPositive();
    }

    // ============================================================
    // 2) 신청 · IDV · 동의
    // ============================================================

    @Test @Order(10)
    void 대출_신청() throws Exception {
        String body = """
                {
                  "customerId":5001,
                  "prodId":%d,
                  "channelCd":"MOBILE",
                  "requestedAmount":30000000,
                  "requestedPeriodMo":36,
                  "loanPurposeCd":"LIVING",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .header("Idempotency-Key", "e2e-appl-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applStatusCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.applNo").exists())
                .andReturn();
        applId = extractData(result).get("applId").asLong();
        assertThat(applId).isPositive();
    }

    @Test @Order(11)
    void 본인확인_IDV() throws Exception {
        String body = """
                { "idvMethodCd":"PASS", "idvTargetCd":"APPLICANT", "mobileNo":"01012345678" }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.idvStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.idvResultCd").value("PASS"))
                .andExpect(jsonPath("$.data.mobileNoMasked").value("010-****-5678"))
                .andReturn();
        idvId = extractData(result).get("idvId").asLong();
    }

    @Test @Order(12)
    void IDV_상태_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/identity-verifications/{idvId}", applId, idvId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idvStatusCd").value("DONE"));
    }

    @Test @Order(13)
    void 신용조회_동의() throws Exception {
        String body = """
                {
                  "consentTypeCd":"CB_INQUIRY",
                  "consentScopeCd":"FULL",
                  "consentTargetCd":"KCB",
                  "consentMethodCd":"E_SIGN"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/credit-consents", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.consentYn").value(true))
                .andExpect(jsonPath("$.data.withdrawnYn").value(false))
                .andReturn();
        csntId = extractData(result).get("csntId").asLong();
    }

    // ============================================================
    // 3) 서류
    // ============================================================

    @Test @Order(20)
    void 서류_업로드() throws Exception {
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "submission_id": "e2e-hold-stub",
                                  "application_id": "any",
                                  "doc_code": "INCOME_PROOF",
                                  "verify_status": "HOLD",
                                  "document_verification": { "confidence_score": 0.6 }
                                }
                                """)));

        MockMultipartFile file = new MockMultipartFile(
                "file", "payslip.pdf", MediaType.APPLICATION_PDF_VALUE,
                "dummy-pdf-content".getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(file)
                        .param("docTypeCd", "INCOME_PROOF"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docStatusCd").value("UPLOADED"))
                .andExpect(jsonPath("$.data.docName").value("payslip.pdf"))
                .andExpect(jsonPath("$.data.docHash").exists())
                .andReturn();
        docId = extractData(result).get("docId").asLong();
    }

    @Test @Order(21)
    void 서류_목록_조회() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/documents", applId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].docId").value(docId));
    }

    @Test @Order(22)
    void 서류_다운로드() throws Exception {
        mockMvc.perform(get("/api/loan-documents/{docId}/download", docId))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test @Order(23)
    void 서류_삭제() throws Exception {
        mockMvc.perform(delete("/api/loan-documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docStatusCd").value("DELETED"));
    }

    // ============================================================
    // 4) 담보
    // ============================================================

    @Test @Order(30)
    void 담보_등록() throws Exception {
        String body = """
                {
                  "colTypeCd":"REAL_ESTATE",
                  "colName":"서울시 강남구 APT",
                  "colAddress":"서울시 강남구 ...",
                  "declaredValue":500000000,
                  "ownershipTypeCd":"SOLE"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.colStatusCd").value("REGISTERED"))
                .andExpect(jsonPath("$.data.colNo").exists())
                .andReturn();
        colId = extractData(result).get("colId").asLong();
    }

    @Test @Order(31)
    void 대출별_담보_목록() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{applId}/collaterals", applId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].colId").value(colId));
    }

    @Test @Order(32)
    void 담보_수정() throws Exception {
        String body = """
                { "declaredValue":550000000, "seniorLienAmount":100000000 }
                """;
        mockMvc.perform(patch("/api/collaterals/{colId}", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.declaredValue").value(550000000))
                .andExpect(jsonPath("$.data.seniorLienAmount").value(100000000));
    }

    @Test @Order(33)
    void 담보_감정평가() throws Exception {
        String body = """
                { "evalMethodCd":"APPRAISAL", "evalAgencyCd":"KAB", "appraisedValue":520000000 }
                """;
        MvcResult result = mockMvc.perform(post("/api/collaterals/{colId}/evaluations", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.evalStatusCd").value("DONE"))
                .andExpect(jsonPath("$.data.appliedValue").value(520000000))
                .andReturn();
        cevalColId = extractData(result).get("cevalColId").asLong();
        assertThat(cevalColId).isPositive();
    }

    @Test @Order(34)
    void 담보_해제() throws Exception {
        String body = """
                { "releaseReasonCd":"LOAN_PAID", "releaseDate":"20261231" }
                """;
        mockMvc.perform(post("/api/collaterals/{colId}/release", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.colStatusCd").value("RELEASED"));
    }

    @Test @Order(35)
    void 해제된_담보_재수정_차단() throws Exception {
        String body = """
                { "declaredValue":600000000 }
                """;
        mockMvc.perform(patch("/api/collaterals/{colId}", colId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_051"));
    }

    // ============================================================
    // 5) 신청 취소
    // ============================================================

    @Test @Order(40)
    void 대출_신청_취소() throws Exception {
        String body = """
                { "cancelReasonCd":"CUST_REQ", "cancelRemark":"마음 바뀜" }
                """;
        mockMvc.perform(post("/api/loan-applications/{applId}/cancel", applId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("CANCELED"));
    }

    @Test @Order(41)
    void 멱등성_중복_신청_방어() throws Exception {
        // 동일 Idempotency-Key 로 재호출 — 기존 신청 그대로 반환되어야 함
        String body = """
                {
                  "customerId":5001,
                  "prodId":%d,
                  "channelCd":"MOBILE",
                  "requestedAmount":30000000,
                  "requestedPeriodMo":36,
                  "loanPurposeCd":"LIVING",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .header("Idempotency-Key", "e2e-appl-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = extractData(result);
        assertThat(data.get("applId").asLong()).isEqualTo(applId);
    }

    // ============================================================
    // 6) 상품 단종
    // ============================================================

    @Test @Order(50)
    void 상품_단종() throws Exception {
        String body = """
                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                """;
        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prodStatusCd").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.saleEndDate").value("20261231"));
    }

    @Test @Order(51)
    void 단종된_상품_재단종_차단() throws Exception {
        String body = """
                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                """;
        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", prodId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_004"));
    }
}
