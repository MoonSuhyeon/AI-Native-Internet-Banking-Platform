package com.bank.loan;

import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보존기한 경과 서류 원본 파기 배치.
 *
 * <p>검증하는 것:
 * <ul>
 *   <li>기한이 지난 서류만 파기되고, 남은 서류는 건드리지 않는다</li>
 *   <li>파기 후에도 서류 row 는 남는다(메타데이터는 증빙)</li>
 *   <li>파기된 서류는 다운로드되지 않는다 — 실물이 없으므로</li>
 *   <li>재실행해도 같은 건을 다시 파기하지 않는다(멱등)</li>
 * </ul>
 */
class LoanDocumentRetentionBatchTest extends AbstractLoanIntegrationTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private LoanDocumentRepository documentRepository;

    private Long applId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createActiveProduct();
        applId = createApplication(prodId);
    }

    @Test
    @DisplayName("기한 경과분만 파기되고 남은 서류는 그대로")
    void 기한_경과분만_파기() throws Exception {
        Long expired = uploadWithRetention(LocalDate.now().minusDays(1));
        Long alive   = uploadWithRetention(LocalDate.now().plusYears(5));

        mockMvc.perform(post("/api/internal/loan-documents/purge-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.purgedDocIds[?(@ == %d)]".formatted(expired)).exists());

        assertThat(documentRepository.findById(expired))
                .as("파기해도 서류 row 는 남는다")
                .hasValueSatisfying(doc -> {
                    assertThat(doc.isPurged()).isTrue();
                    assertThat(doc.getDocName()).isNotNull();
                    assertThat(doc.getDocHash()).as("무결성 해시는 증빙으로 남는다").isNotNull();
                });

        assertThat(documentRepository.findById(alive))
                .as("기한이 남은 서류는 건드리지 않는다")
                .hasValueSatisfying(doc -> assertThat(doc.isPurged()).isFalse());
    }

    @Test
    @DisplayName("파기된 서류는 다운로드되지 않는다 (LOAN_041)")
    void 파기_후_다운로드_404() throws Exception {
        Long docId = uploadWithRetention(LocalDate.now().minusDays(1));

        mockMvc.perform(get("/api/loan-documents/{docId}/download", docId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internal/loan-documents/purge-expired"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-documents/{docId}/download", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_041"));
    }

    @Test
    @DisplayName("재실행해도 같은 건을 다시 파기하지 않는다")
    void 재실행_멱등() throws Exception {
        Long docId = uploadWithRetention(LocalDate.now().minusDays(1));

        mockMvc.perform(post("/api/internal/loan-documents/purge-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purgedDocIds[?(@ == %d)]".formatted(docId)).exists());

        // 두 번째 실행에서는 대상에서 빠진다 — purged_at 이 찍혔으므로 조회 자체에 안 걸린다.
        mockMvc.perform(post("/api/internal/loan-documents/purge-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.purgedDocIds[?(@ == %d)]".formatted(docId)).doesNotExist());
    }

    @Test
    @DisplayName("보존기한이 없는 서류는 대상이 아니다")
    void 보존기한_없으면_제외() throws Exception {
        Long docId = upload();   // retention_until 미설정

        mockMvc.perform(post("/api/internal/loan-documents/purge-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purgedDocIds[?(@ == %d)]".formatted(docId)).doesNotExist());

        assertThat(documentRepository.findById(docId))
                .hasValueSatisfying(doc -> assertThat(doc.isPurged()).isFalse());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Long uploadWithRetention(LocalDate retentionUntil) throws Exception {
        Long docId = upload();
        LoanDocument doc = documentRepository.findById(docId).orElseThrow();
        doc.markRetained(retentionUntil.format(YMD));
        documentRepository.save(doc);
        return docId;
    }

    private Long upload() throws Exception {
        // submission_id 는 varchar(36) 이고 제출 이력의 PK 다. 업로드마다 새로 스텁하지 않으면
        // 같은 테스트 안 두 번째 업로드에서 키가 충돌한다.
        DOC_AGENT_MOCK.resetAll();
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "submission_id": "%s",
                                  "doc_code": "INCOME_PROOF",
                                  "verify_status": "AUTO_PASS",
                                  "document_verification": { "confidence_score": 0.9 }
                                }
                                """.formatted(UUID.randomUUID()))));

        MockMultipartFile file = new MockMultipartFile(
                "file", "income.pdf", MediaType.APPLICATION_PDF_VALUE, "retention-test".getBytes());
        MvcResult result = mockMvc.perform(
                        multipart("/api/loan-applications/{applId}/documents", applId)
                                .file(file)
                                .param("docTypeCd", "INCOME_PROOF"))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("docId").asLong();
    }

    private Long createActiveProduct() throws Exception {
        String code = "RETENTION_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"보존기한 배치 테스트","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":450,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":"N","guarantorRequiredYn":"N"
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
        Long id = extractData(result).get("prodId").asLong();
        mockMvc.perform(patch("/api/loan-products/{prodId}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private Long createApplication(Long prodId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":8001, "prodId":%d, "channelCd":"MOBILE",
                                  "requestedAmount":10000000, "requestedPeriodMo":24,
                                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(prodId)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(result).get("applId").asLong();
    }
}
