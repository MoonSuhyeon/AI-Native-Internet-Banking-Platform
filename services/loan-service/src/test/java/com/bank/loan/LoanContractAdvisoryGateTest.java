package com.bank.loan;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 *
 * <p><b>WireMock 을 걷어냈다.</b> 예전에는 별도 프로세스인 advisory-service 를 흉내 내는
 * HTTP stub 으로 각 분기를 만들었다. 그런데 그 서비스는 빌드에 포함된 적이 없어서,
 * stub 이 흉내 내던 대상이 애초에 존재하지 않았다 — 테스트는 실제 동작이 아니라
 * 상상 속 응답을 검증하고 있었다.
 *
 * <p>지금은 어드바이저리가 같은 프로세스 안에 있으므로 리포트를 실제로 저장하고
 * 게이트가 그것을 읽는지 본다. 그래야 조회 조건(revId 매칭·삭제 여부·상태 코드)까지
 * 함께 검증된다.
 *
 * <p>fail-open 분기는 없앴다. 네트워크 호출이 사라져 "외부 서비스 장애" 라는 상태
 * 자체가 없어졌기 때문이다. 조회가 실패하면 그건 장애가 아니라 결함이다.
 */
class LoanContractAdvisoryGateTest extends AbstractLoanIntegrationTest {

    /** 이 클래스가 만든 리포트를 알아보기 위한 표식. */
    private static final String GATE_TITLE = "게이트 테스트";

    @Autowired
    private ReviewAdvisoryReportRepository advisoryReportRepository;

    private Long prodId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createActiveProduct();
    }

    /**
     * 이 클래스가 만든 리포트만 지운다.
     *
     * <p>{@code deleteAll()} 은 쓸 수 없다. 다른 시험이 만든 리포트에는
     * {@code review_advisory_signal} 자식 행이 붙어 있어 FK 로 막히고, 컨테이너를
     * 공유하는 이 하네스에서는 그 실패가 이 클래스 전체를 쓰러뜨린다.
     *
     * <p>지우는 이유는 격리다. 남겨 두면 앞 시험의 미확인 CRITICAL 이 뒤 시험을 막아
     * "게이트가 동작한다" 는 잘못된 초록이 나온다.
     */
    @BeforeEach
    void clearAdvisoryReports() {
        advisoryReportRepository.deleteAll(
                advisoryReportRepository.findAll().stream()
                        .filter(r -> GATE_TITLE.equals(r.getAdvrTitle()))
                        .toList());
    }

    @Test
    @DisplayName("CRITICAL 이 미확인이면 약정 체결이 막힌다 (LOAN_201)")
    void critical_미확인_차단() throws Exception {
        Fixture f = approvedApplicationWithReview();
        givenReport(f.revId, "CRITICAL", "OPEN");

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
        givenReport(f.revId, "CRITICAL", "ACKED");

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 RESOLVED 면 통과")
    void critical_resolved_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        givenReport(f.revId, "CRITICAL", "RESOLVED");

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 아니면 미확인이어도 통과 — 게이트는 CRITICAL 만 막는다")
    void 비critical_미확인_통과() throws Exception {
        Fixture f = approvedApplicationWithReview();
        givenReport(f.revId, "WARNING", "OPEN");

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CRITICAL 이 여러 건이면 하나라도 미확인이면 막힌다")
    void critical_혼재_차단() throws Exception {
        Fixture f = approvedApplicationWithReview();
        givenReport(f.revId, "CRITICAL", "ACKED");
        givenReport(f.revId, "CRITICAL", "OPEN");

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_201"));
    }

    @Test
    @DisplayName("리포트가 없으면 통과한다 — 게이트는 CRITICAL 미확인만 막는다")
    void 리포트_없으면_통과() throws Exception {
        // 예전에는 이 자리에 "advisory-service 장애면 통과(fail-open)" 시험이 있었다.
        // 네트워크 호출이 없어져 그 상태가 사라졌으므로, 남는 정상 경로만 남긴다.
        Fixture f = approvedApplicationWithReview();

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(f.applId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본심사가 없으면 게이트를 적용하지 않는다 — 남의 심사건 CRITICAL 은 이 건을 막지 않는다")
    void 리뷰_없으면_통과() throws Exception {
        // 미확인 CRITICAL 을 **다른 심사건**에 붙여 둔다. 게이트가 revId 로 맞추지 않고
        // "어딘가에 CRITICAL 이 있으면 막는다" 로 동작하면 여기서 걸린다.
        //
        // 예전에는 존재하지 않는 revId(999999) 를 stub 으로 흉내 냈다. HTTP stub 은
        // 아무 숫자나 받아 주지만 실제 원장은 FK 로 거절한다 — 검증하던 상황 자체가
        // 성립할 수 없는 것이었다.
        Fixture other = approvedApplicationWithReview();
        givenReport(other.revId, "CRITICAL", "OPEN");

        Long applId = createApplication();
        forceApprove(applId);

        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody(applId)))
                .andExpect(status().isCreated());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private record Fixture(Long applId, Long revId) {}

    /** 어드바이저리 리포트를 실제로 저장한다 — 게이트가 DB 에서 읽는 그 데이터다. */
    private void givenReport(Long revId, String severityCd, String statusCd) {
        advisoryReportRepository.save(ReviewAdvisoryReport.builder()
                .revId(revId)
                .ruleId(1L)
                .advisoryTypeCd("BIAS_CHECK")
                .severityCd(severityCd)
                .advrStatusCd(statusCd)
                .advrTitle(GATE_TITLE)
                .advrSummary("요약")
                .targetReviewerId(1L)
                .generatedAt(OffsetDateTime.now())
                .build());
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
