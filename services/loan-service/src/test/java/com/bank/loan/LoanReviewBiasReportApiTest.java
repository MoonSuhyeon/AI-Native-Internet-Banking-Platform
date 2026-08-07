package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편향 리포트 수신 내부 API + advice 목록 조회 통합 테스트.
 *
 * 시나리오:
 *   10) 본심사 run() → BIAS_REVIEWING
 *   20) POST /api/internal/loan-reviews/{revId}/bias-report → 201, advice 저장
 *   21) 중복 전송(재전송) — 상태가 이미 BIAS_REVIEWING 이면 severity 갱신
 *   30) GET /api/loan-reviews/{revId}/advices → advice 목록 2건 최신순
 *   40) revId 없는 bias-report → 404
 *
 * 날짜 격리: 연도 2034 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewBiasReportApiTest extends AbstractLoanIntegrationTest {

    private static Long prodId;
    private static Long applId;
    private static Long revId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);
        applId = createApplication(prodId, 20340001L);
        prepFullyEligible(applId);
    }

    @Test @Order(10)
    void 본심사_run_후_BIAS_REVIEWING() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revTypeCd":"MANUAL","revDecisionCd":"APPROVED",
                                  "reviewerId":20340101
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andReturn();
        revId = extractData(r).get("revId").asLong();
    }

    @Test @Order(20)
    void 편향_리포트_수신_성공() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", revId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"HIGH",
                                  "summary":"연령 기반 불이익 가능성 감지",
                                  "findings":[
                                    {"code":"AGE_BIAS","result":"DETECTED","detail":"40대 이상 신청자 거절률 이상"},
                                    {"code":"GENDER_BIAS","result":"NOT_DETECTED","detail":null}
                                  ],
                                  "model":"claude-opus-4-7",
                                  "modelVersion":"20250514",
                                  "promptHash":"abc123",
                                  "inputToken":1200,
                                  "outputToken":400,
                                  "latencyMs":1500
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.adviceId").isNumber())
                .andExpect(jsonPath("$.data.revId").value(revId))
                .andExpect(jsonPath("$.data.adviceTypeCd").value("BIAS_CHECK"))
                .andExpect(jsonPath("$.data.severityCd").value("HIGH"))
                .andExpect(jsonPath("$.data.model").value("claude-opus-4-7"))
                .andExpect(jsonPath("$.data.latencyMs").value(1500));
    }

    @Test @Order(21)
    void 편향_리포트_재전송_severity_갱신() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", revId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"MEDIUM",
                                  "summary":"2차 분석: 경미한 패턴만 감지",
                                  "findings":[
                                    {"code":"AGE_BIAS","result":"LOW_SIGNAL","detail":"재분석 결과 약한 신호"}
                                  ],
                                  "model":"claude-opus-4-7",
                                  "modelVersion":"20250514",
                                  "promptHash":"def456",
                                  "inputToken":800,
                                  "outputToken":200,
                                  "latencyMs":900
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.severityCd").value("MEDIUM"));
    }

    @Test @Order(30)
    void advice_목록_최신순_2건() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/advices", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].severityCd").value("MEDIUM"))
                .andExpect(jsonPath("$.data[1].severityCd").value("HIGH"));
    }

    @Test @Order(50)
    void advisory_리포트_목록_빈_배열_반환() throws Exception {
        mockMvc.perform(get("/api/loan-reviews/{revId}/advisory-reports", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test @Order(51)
    void advisory_리포트_목록_항목_반환() throws Exception {
        ADVISORY_MOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/advisory/reports"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"advrId":1,"revId":%d,"advisoryTypeCd":"RISK","severityCd":"HIGH",
                                  "advrStatusCd":"OPEN","advrTitle":"고위험 감지","advrSummary":"요약","targetReviewerId":null}]
                                """.formatted(revId))));

        mockMvc.perform(get("/api/loan-reviews/{revId}/advisory-reports", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].severityCd").value("HIGH"))
                .andExpect(jsonPath("$.data[0].advrTitle").value("고위험 감지"));
    }

    @Test @Order(52)
    void advisory_서비스_장애_시_빈_목록_반환_fail_open() throws Exception {
        ADVISORY_MOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/advisory/reports"))
                .willReturn(WireMock.aResponse().withStatus(500)));

        mockMvc.perform(get("/api/loan-reviews/{revId}/advisory-reports", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test @Order(40)
    void 존재하지_않는_revId_편향_리포트_수신_404() throws Exception {
        mockMvc.perform(post("/api/internal/loan-reviews/{revId}/bias-report", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "severityCd":"LOW",
                                  "summary":"테스트",
                                  "findings":[],
                                  "model":"test","modelVersion":"1","promptHash":"x",
                                  "inputToken":1,"outputToken":1,"latencyMs":1
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Long createCreditProduct() throws Exception {
        String code = "BRP_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"편향리포트 신용대출","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":false,"guarantorRequiredYn":false
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{id}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prodStatusCd":"ACTIVE"}
                                """))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId, long customerId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":%d,"prodId":%d,"channelCd":"MOBILE",
                                  "requestedAmount":30000000,"requestedPeriodMo":36,
                                  "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(customerId, prodId)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void prepFullyEligible(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prescResultCd":"PASS","estimatedScore":700}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cevalEngine":"KCB","cevalDecisionCd":"APPROVE",
                                  "cevalScore":700,"evalLimitAmount":50000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annualIncomeAmt":80000000,"newAnnualRepayAmt":10000000}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112034"}
                                """))
                .andExpect(status().isCreated());
    }
}
