package com.bank.loan;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.engine.rules.DsrThresholdOverrideRule;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드바이저리 외부 REST API 통합 — happy path + 권한 거부 + reviewer 타인 리포트 차단.
 * 격리 reviewer id 99_601(A) / 99_602(B). 테스트 연도 2060.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvisoryExternalApiFlowTest extends AbstractLoanIntegrationTest {

    // 테스트 환경의 CurrentActorProvider 가 SYSTEM(0L) 을 반환하므로,
    // REVIEWER role + actorId 자동매핑이 본인 매칭되도록 A=SYSTEM 으로 둠. B 는 임의 다른 ID.
    private static final Long REVIEWER_A = com.bank.common.persistence.CurrentActorProvider.SYSTEM;
    private static final Long REVIEWER_B = 99_602L;

    @Autowired ReviewAdvisoryReportRepository reportRepo;
    @Autowired ReviewAdvisoryRuleRepository ruleRepo;

    private Long advrIdA;
    private Long advrIdB;
    private Long sampleRuleId;

    @BeforeAll
    void seedReports() {
        Long ruleIdDsr = ruleRepo
                .findByRuleCdAndDeletedAtIsNull(DsrThresholdOverrideRule.RULE_CD)
                .map(ReviewAdvisoryRule::getRuleId)
                .orElseThrow();
        sampleRuleId = ruleIdDsr;

        advrIdA = reportRepo.save(buildReport(REVIEWER_A,
                ReviewAdvisoryReport.SEVERITY_CRITICAL, ruleIdDsr,
                "API-A-2060", randomId())).getAdvrId();

        advrIdB = reportRepo.save(buildReport(REVIEWER_B,
                ReviewAdvisoryReport.SEVERITY_WARN, ruleIdDsr,
                "API-B-2060", randomId())).getAdvrId();
    }

    // ============================================================
    // GET /reports — 본인 필터링 + auditor 전체 조회
    // ============================================================

    @Test @Order(10)
    void REVIEWER_본인_대상_리포트만_노출() throws Exception {
        mockMvc.perform(get("/api/advisory/reports").header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.advrId == " + advrIdA + ")]").exists())
                .andExpect(jsonPath("$.data.items[?(@.advrId == " + advrIdB + ")]").doesNotExist());
    }

    @Test @Order(11)
    void AUDITOR_타인_리포트도_노출() throws Exception {
        // AUDITOR 는 REVIEWER 필터 우회 — DB 공유 환경에서 본 클래스의 두 리포트 모두 결과에 포함되어야 함
        mockMvc.perform(get("/api/advisory/reports")
                        .header("X-Actor-Role", "AUDITOR")
                        .param("targetReviewerId", String.valueOf(REVIEWER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.advrId == " + advrIdB + ")]").exists());
    }

    // ============================================================
    // GET /reports/{id} — 본인 리포트 OK, 타인 리포트 404
    // ============================================================

    @Test @Order(20)
    void REVIEWER_본인_리포트_상세_OK() throws Exception {
        mockMvc.perform(get("/api/advisory/reports/{id}", advrIdA).header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.advrId").value(advrIdA))
                .andExpect(jsonPath("$.data.severityCd").value("CRITICAL"));
    }

    @Test @Order(21)
    void REVIEWER_타인_리포트_접근_404_LOAN_190() throws Exception {
        // REVIEWER A 가 B 의 리포트 상세 시도 → 존재 자체를 숨김
        mockMvc.perform(get("/api/advisory/reports/{id}", advrIdB).header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_190"));
    }

    // ============================================================
    // POST /reports/{id}/view + /ack
    // ============================================================

    @Test @Order(30)
    void 본인_리포트_조회_마킹_OPEN_VIEWED() throws Exception {
        mockMvc.perform(post("/api/advisory/reports/{id}/view", advrIdA).header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.advrStatusCd").value("VIEWED"));
    }

    @Test @Order(31)
    void 본인_리포트_ack_등록_ACKED() throws Exception {
        String body = """
                {
                  "ackResponseCd":"MAINTAIN",
                  "decisionChangeYn":"N",
                  "ackReasonCd":"REVIEWER_JUDGMENT",
                  "ackRemark":"정책 예외 검토 후 유지",
                  "beforeDecisionCd":"APPROVED",
                  "afterDecisionCd":"APPROVED"
                }
                """;
        mockMvc.perform(post("/api/advisory/reports/{id}/ack", advrIdA)
                        .header("X-Actor-Role", "REVIEWER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ackResponseCd").value("MAINTAIN"));

        ReviewAdvisoryReport reloaded = reportRepo.findById(advrIdA).orElseThrow();
        assertThat(reloaded.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_ACKED);
    }

    @Test @Order(32)
    void REVIEWER_타인_리포트_ack_시도_404_LOAN_190() throws Exception {
        String body = """
                { "ackResponseCd":"MAINTAIN", "decisionChangeYn":"N" }
                """;
        mockMvc.perform(post("/api/advisory/reports/{id}/ack", advrIdB)
                        .header("X-Actor-Role", "REVIEWER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_190"));
    }

    // ============================================================
    // /rules — auditor/admin 분기
    // ============================================================

    @Test @Order(40)
    void REVIEWER_가_rules_목록_접근_403_COMMON_403() throws Exception {
        mockMvc.perform(get("/api/advisory/rules").header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_403"));
    }

    @Test @Order(41)
    void AUDITOR_rules_목록_OK() throws Exception {
        mockMvc.perform(get("/api/advisory/rules").header("X-Actor-Role", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(5))
                .andExpect(jsonPath("$.data.items[?(@.ruleCd == 'DSR_THRESHOLD_OVERRIDE')]").exists());
    }

    @Test @Order(42)
    void AUDITOR_가_rules_수정_시도_403() throws Exception {
        String body = """
                { "activeYn":"N", "changeReasonCd":"OPS_TEST", "changeRemark":"감사자 시도" }
                """;
        mockMvc.perform(put("/api/advisory/rules/{id}", sampleRuleId)
                        .header("X-Actor-Role", "AUDITOR")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_403"));
    }

    @Test @Order(43)
    void ADMIN_rules_비활성화_후_재활성화_OK() throws Exception {
        String off = """
                { "activeYn":"N", "changeReasonCd":"OPS_TEST", "changeRemark":"임시 비활성" }
                """;
        mockMvc.perform(put("/api/advisory/rules/{id}", sampleRuleId)
                        .header("X-Actor-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(off))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeYn").value("N"));

        String on = """
                { "activeYn":"Y", "changeReasonCd":"OPS_TEST", "changeRemark":"재활성" }
                """;
        mockMvc.perform(put("/api/advisory/rules/{id}", sampleRuleId)
                        .header("X-Actor-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeYn").value("Y"));
    }

    // ============================================================
    // /stats — auditor/admin 만
    // ============================================================

    @Test @Order(50)
    void REVIEWER_가_stats_접근_403() throws Exception {
        mockMvc.perform(get("/api/advisory/stats/reviewers/{id}", REVIEWER_A)
                        .header("X-Actor-Role", "REVIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_403"));
    }

    @Test @Order(51)
    void AUDITOR_stats_OK() throws Exception {
        mockMvc.perform(get("/api/advisory/stats/reviewers/{id}", REVIEWER_A)
                        .header("X-Actor-Role", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewerId").value(REVIEWER_A))
                .andExpect(jsonPath("$.data.totalReports").exists())
                .andExpect(jsonPath("$.data.ackResponseCounts").exists())
                .andExpect(jsonPath("$.data.ruleTriggerCounts").exists());
    }

    // ============================================================
    // helpers
    // ============================================================

    private static Long randomId() {
        return ThreadLocalRandom.current().nextLong(2_060_000L, 2_069_999L);
    }

    private static ReviewAdvisoryReport buildReport(Long targetReviewerId, String severity,
                                                    Long ruleId, String tag, Long revId) {
        return ReviewAdvisoryReport.builder()
                .revId(revId)
                .ruleId(ruleId)
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd(severity)
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle(tag)
                .advrSummary("외부 API 통합 테스트 - " + tag)
                .targetReviewerId(targetReviewerId)
                .generatedAt(OffsetDateTime.now())
                .build();
    }
}
