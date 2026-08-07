package com.bank.loan;

import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.document.repository.LoanDocumentSubmissionRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * doc-agent 연동 통합 테스트.
 *
 * 시나리오:
 *   10) POST /documents → AUTO_PASS  → doc_status_cd = VERIFIED, loan_document_submission insert
 *   20) POST /documents → NEEDS_RESUBMIT → doc_status_cd = REJECTED
 *   30) POST /documents → HOLD, 이후 doc-agent.routed NEEDS_RESUBMIT 이벤트 → REJECTED
 *   40) doc-agent.fraud.audit FRAUD_CONFIRMED 이벤트 → 신청 REJECTED
 *
 * 날짜 격리: 연도 2055 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanDocumentDocAgentFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private LoanDocumentRepository documentRepository;
    @Autowired private LoanDocumentSubmissionRepository submissionRepository;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    private Long prodId;
    private Long applId;
    private String applNo;
    private Long fraudApplId;
    private String fraudApplNo;

    @BeforeAll
    void setup() throws Exception {
        prodId = createProduct("DOC_AGENT_" + uniq());
        activateProduct(prodId);
        applId = createApplication(prodId, 20550001L);
        applNo = applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow().getApplNo();
        fraudApplId = createApplication(prodId, 20550002L);
        fraudApplNo = applicationRepository.findByApplIdAndDeletedAtIsNull(fraudApplId)
                .orElseThrow().getApplNo();
    }

    @BeforeEach
    void resetStubs() {
        DOC_AGENT_MOCK.resetAll();
    }

    // ─── 10) AUTO_PASS ────────────────────────────────────────────────────────

    @Test @Order(10)
    void AUTO_PASS_응답시_서류_VERIFIED_전이_및_submission_insert() throws Exception {
        String submissionId = "sub-auto-" + uniq();
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "submission_id": "%s",
                                  "application_id": "%s",
                                  "doc_code": "EMPLOYMENT_CERT",
                                  "verify_status": "AUTO_PASS",
                                  "document_verification": { "confidence_score": 0.95 },
                                  "pipeline_stage": "L5_VERIFY_COMPLETE"
                                }
                                """.formatted(submissionId, applNo))));

        MvcResult result = mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(pdfFile())
                        .param("docTypeCd", "EMPLOYMENT_CERT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docStatusCd").value("VERIFIED"))
                .andReturn();

        long docId = extractData(result).get("docId").asLong();

        assertThat(documentRepository.findByDocIdAndDeletedAtIsNull(docId))
                .hasValueSatisfying(doc -> assertThat(doc.getDocStatusCd()).isEqualTo("VERIFIED"));

        assertThat(submissionRepository.findById(submissionId))
                .hasValueSatisfying(sub -> {
                    assertThat(sub.getVerifyStatus()).isEqualTo("AUTO_PASS");
                    assertThat(sub.getDocCode()).isEqualTo("EMPLOYMENT_CERT");
                    assertThat(sub.getApplicationId()).isEqualTo(applNo);
                });
    }

    // ─── 20) NEEDS_RESUBMIT via upload ───────────────────────────────────────

    @Test @Order(20)
    void NEEDS_RESUBMIT_응답시_서류_REJECTED_전이() throws Exception {
        String submissionId = "sub-needs-" + uniq();
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "submission_id": "%s",
                                  "application_id": "%s",
                                  "doc_code": "EMPLOYMENT_CERT",
                                  "verify_status": "NEEDS_RESUBMIT",
                                  "document_verification": { "confidence_score": 0.3 }
                                }
                                """.formatted(submissionId, applNo))));

        mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(pdfFile())
                        .param("docTypeCd", "EMPLOYMENT_CERT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docStatusCd").value("REJECTED"));
    }

    // ─── 30) HOLD → Kafka doc-agent.routed NEEDS_RESUBMIT ───────────────────

    @Test @Order(30)
    void doc_agent_routed_NEEDS_RESUBMIT_이벤트로_서류_REJECTED_전이() throws Exception {
        String submissionId = "sub-hold-" + uniq();
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "submission_id": "%s",
                                  "application_id": "%s",
                                  "doc_code": "ID_CARD",
                                  "verify_status": "HOLD",
                                  "document_verification": { "confidence_score": 0.6 }
                                }
                                """.formatted(submissionId, applNo))));

        MvcResult result = mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(pdfFile())
                        .param("docTypeCd", "ID_CARD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docStatusCd").value("UPLOADED"))
                .andReturn();

        long docId = extractData(result).get("docId").asLong();

        kafkaTemplate.send("doc-agent.routed", submissionId, """
                {
                  "event_type": "SUBMISSION_ROUTED",
                  "submission_id": "%s",
                  "application_id": "%s",
                  "doc_code": "ID_CARD",
                  "verify_status": "NEEDS_RESUBMIT",
                  "occurred_at": "2055-05-28T10:00:00Z"
                }
                """.formatted(submissionId, applNo));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(documentRepository.findByDocIdAndDeletedAtIsNull(docId))
                        .hasValueSatisfying(doc ->
                                assertThat(doc.getDocStatusCd()).isEqualTo("REJECTED")));
    }

    // ─── 35) doc-agent 장애 → 검증 보류(PENDING) 강등, 업로드는 성공 ────────────

    @Test @Order(35)
    void doc_agent_장애시_서류_PENDING_보류_되고_업로드는_성공() throws Exception {
        // doc-agent 가 5xx 를 반복 반환 → 클라이언트 재시도(3회) 소진 → DocAgentException.
        // upload() 는 이를 잡아 트랜잭션을 롤백하지 않고 검증만 보류(PENDING)로 강등한다.
        DOC_AGENT_MOCK.stubFor(WireMock.post(urlEqualTo("/api/documents/submit"))
                .willReturn(aResponse().withStatus(500)));

        MvcResult result = mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(pdfFile())
                        .param("docTypeCd", "EMPLOYMENT_CERT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docStatusCd").value("UPLOADED"))
                .andExpect(jsonPath("$.data.verifyResultCd").value("PENDING"))
                .andReturn();

        long docId = extractData(result).get("docId").asLong();

        assertThat(documentRepository.findByDocIdAndDeletedAtIsNull(docId))
                .hasValueSatisfying(doc -> {
                    assertThat(doc.getDocStatusCd()).isEqualTo("UPLOADED");
                    assertThat(doc.getVerifyResultCd()).isEqualTo("PENDING");
                    // doc-agent 미응답이므로 submissionId(=doc_url) 는 보존되지 않는다.
                    assertThat(doc.getDocUrl()).isNull();
                });
    }

    // ─── 40) Kafka doc-agent.fraud.audit FRAUD_CONFIRMED ────────────────────

    @Test @Order(40)
    void doc_agent_fraud_audit_FRAUD_CONFIRMED_이벤트로_신청_REJECTED_전이() {
        String submissionId = "sub-fraud-" + uniq();

        kafkaTemplate.send("doc-agent.fraud.audit", submissionId, """
                {
                  "event_type": "FRAUD_CONFIRMED",
                  "submission_id": "%s",
                  "application_id": "%s",
                  "doc_code": "EMPLOYMENT_CERT",
                  "reviewer_id": "fraud-reviewer-01",
                  "retention_until": "2060-05-28",
                  "occurred_at": "2055-05-28T11:00:00Z"
                }
                """.formatted(submissionId, fraudApplNo));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(applicationRepository.findByApplIdAndDeletedAtIsNull(fraudApplId))
                        .hasValueSatisfying(appl ->
                                assertThat(appl.currentStatus()).isEqualTo("REJECTED")));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, "dummy".getBytes());
    }

    private Long createProduct(String code) throws Exception {
        String body = """
                {
                  "prodCd":"%s","prodName":"doc-agent 연동 테스트","loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                  "minAmount":1000000,"maxAmount":100000000,
                  "minPeriodMo":12,"maxPeriodMo":60,
                  "collateralRequiredYn":false,"guarantorRequiredYn":false
                }
                """.formatted(code);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long pid) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long pid, long customerId) throws Exception {
        String body = """
                {
                  "customerId":%d,"prodId":%d,"channelCd":"MOBILE",
                  "requestedAmount":10000000,"requestedPeriodMo":12,
                  "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"
                }
                """.formatted(customerId, pid);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(result).get("applId").asLong();
    }
}
