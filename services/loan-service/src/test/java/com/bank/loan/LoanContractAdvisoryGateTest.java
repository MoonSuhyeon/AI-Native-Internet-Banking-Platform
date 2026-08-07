package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약정 체결의 4-eye advisory 게이트.
 *
 * <p>본심사에 CRITICAL Advisory 리포트가 붙어 있는데 아무도 확인(ACK)하지 않았다면
 * 약정을 체결할 수 없어야 한다(LOAN_201). 이것이 "AI 가 위험을 지적했는데 사람이
 * 그냥 넘어가는" 경로를 막는 유일한 차단막이다.
 *
 * <p>이 테스트가 없던 동안 하네스의 advisory 기본 stub 이 항상 빈 배열을 돌려주고 있어
 * 게이트가 한 번도 발화하지 않았다 — 전 테스트가 초록이지만 규칙은 검증되지 않는 상태였다.
 * 그래서 여기서는 stub 을 명시적으로 갈아끼워 각 분기를 강제한다.
 *
 * <p>advisory-service 장애 시 통과시키는 것(fail-open)도 의도된 설계라 함께 박제한다.
 * 외부 조언 서비스가 죽었다고 정상 여신 업무가 멈추면 안 된다는 판단이다.
 */
class LoanContractAdvisoryGateTest extends AbstractLoanIntegrationTest {

    private static final String REPORTS_PATH = "/api/advisory/reports";

    private Long prodId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createActiveProduct();
    }

    @BeforeEach
    void clearAdvisoryStubs() {
        ADVISORY_MOCK.resetAll();
    }

    /**
     * 공유 WireMock 이므로 이 클래스가 바꾼 stub 을 원상복구한다.
     * 복구하지 않으면 뒤에 도는 다른 클래스가 이 클래스의 stub 을 물려받는다.
     */
    @AfterAll
    void restoreDefaultAdvisoryStub() {
        ADVISORY_MOCK.resetAll();
        ADVISORY_MOCK.stubFor(WireMock.get(urlPathEqualTo(REPORTS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
    }

    @Test
    @DisplayName("CRITICAL 이 미확인이면 약정 체결이 막힌다 (LOAN_201)")
    void critical_미확인_차단() throws Exception {
        Fixture f = approvedApplicationWithReview();
        stubReports(report(f.revId, "CRITICAL", "OPEN"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_201"));
    }

    @Test
    @DisplayName("CRITICAL 이 ACKED 면 통과")
    void critical_acked_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        stubReports(report(f.revId, "CRITICAL", "ACKED"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 RESOLVED 면 통과")
    void critical_resolved_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        stubReports(report(f.revId, "CRITICAL", "RESOLVED"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 아니면 미확인이어도 통과 — 게이트는 CRITICAL 만 막는다")
    void 비critical_미확인_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        stubReports(report(f.revId, "WARNING", "OPEN"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 여러 건이면 하나라도 미확인이면 막힌다")
    void critical_혼재_차단() throws Exception {
        Fixture f = approvedApplicationWithReview();
        stubReports(report(f.revId, "CRITICAL", "ACKED")
                + "," + report(f.revId, "CRITICAL", "OPEN"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_201"));
    }

    @Test
    @DisplayName("advisory-service 장애면 통과한다 (fail-open — 외부 조언 장애가 여신을 멈추지 않는다)")
    void advisory_장애_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        ADVISORY_MOCK.stubFor(WireMock.get(urlPathEqualTo(REPORTS_PATH))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본심사가 없으면 게이트를 적용하지 않는다")
    void 리뷰_없으면_통과() throws Exception {
        Long applId = createApplication();
        forceApprove(applId);
        stubReports(report(999_999L, "CRITICAL", "OPEN"));

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(applId)))
                .andExpect(status().isCreated());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private record Fixture(Long applId, Long revId) {}

    private void stubReports(String reportsJson) {
        ADVISORY_MOCK.stubFor(WireMock.get(urlPathEqualTo(REPORTS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + reportsJson + "]")));
    }

    private String report(Long revId, String severityCd, String statusCd) {
        return """
                {
                  "advrId": %d, "revId": %d,
                  "advisoryTypeCd": "BIAS_CHECK",
                  "severityCd": "%s",
                  "advrStatusCd": "%s",
                  "advrTitle": "게이트 테스트",
                  "advrSummary": "요약",
                  "targetReviewerId": "1"
                }
                """.formatted(System.nanoTime() % 1_000_000, revId, severityCd, statusCd);
    }

    /** APPROVED 신청 + 그에 붙은 본심사를 만들어 게이트가 발화할 조건을 갖춘다. */
    private Fixture approvedApplicationWithReview() throws Exception {
        Long applId = createApplication();
        forceApprove(applId);
        Long revId = reviewRepository.save(LoanReview.builder()
                .applId(applId)
                .revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(LoanReview.DECISION_APPROVED)
                .approvedAmount(10_000_000L)
                .approvedRateBps(500)
                .approvedPeriodMo(24)
                .reviewerId(1L)
                .reviewedAt(OffsetDateTime.now())
                .approvedAt(OffsetDateTime.now())
                .build()).getRevId();
        return new Fixture(applId, revId);
    }

    /**
     * 본심사 API 를 거치지 않고 강제 전이한다.
     * 이 테스트의 관심사는 advisory 게이트뿐이라 심사 흐름은 재현하지 않는다.
     */
    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private Long createActiveProduct() throws Exception {
        String code = "ADVGATE_" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "prodCd":"%s","prodName":"advisory 게이트 테스트","loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":450,
                  "minAmount":1000000,"maxAmount":100000000,
                  "minPeriodMo":12,"maxPeriodMo":60,
                  "collateralRequiredYn":false,"guarantorRequiredYn":false
                }
                """.formatted(code);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        Long id = extractData(result).get("prodId").asLong();

        mockMvc.perform(patch("/api/loan-products/{prodId}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prodStatusCd\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private Long createApplication() throws Exception {
        String body = """
                {
                  "customerId":7001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":10000000, "requestedPeriodMo":24,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return extractData(result).get("applId").asLong();
    }

    private String contractBody(Long applId) {
        return """
                {
                  "applId":%d,
                  "contractedAmount":10000000,
                  "contractedPeriodMo":24,
                  "baseRateBps":450,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId);
    }
}
