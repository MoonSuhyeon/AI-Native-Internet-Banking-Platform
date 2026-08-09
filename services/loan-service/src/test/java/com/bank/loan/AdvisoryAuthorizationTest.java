package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드바이저리 API 가 <b>권한이 없을 때 막는지</b> 확인한다.
 *
 * <p><b>왜 별도 파일인가.</b> 기존 흐름 테스트들은 하네스 기본 헤더로
 * {@code ROLE_ADMIN} 을 포함한 역할 전체를 달고 요청한다. 덕분에 정상 경로는 잘
 * 검증되지만, <b>거부되는 경우는 한 번도 지나지 않는다</b> — 가드를 통째로 지워도
 * 그 테스트들은 전부 초록이다.
 *
 * <p>실제로 이 코드가 그런 상태였다. 권한 검사가 22곳에 있었지만 읽는 헤더가
 * {@code X-Actor-Role} 이라 게이트웨이가 지우지 못했고, 호출자가 스스로 적은 값으로
 * 판정하고 있었다. 그런데도 테스트는 전부 통과했다.
 *
 * <p>그래서 여기서는 권한을 <b>낮춰서</b> 부른다. 낮은 권한으로 열리면 그것이 결함이다.
 */
class AdvisoryAuthorizationTest extends AbstractLoanIntegrationTest {

    /** 심사역 권한만 가진 사람. 자문 룰을 바꾸거나 정책 문서를 심을 수 없어야 한다. */
    private static final String REVIEWER_ONLY = "ROLE_HQ_REVIEWER";

    /** 감사 권한. 전체 조회는 되지만 변경은 안 된다. */
    private static final String AUDITOR_ONLY = "ROLE_COMPLIANCE";

    // ── 정책 문서 — AI 가 인용할 근거 ────────────────────────────────────────

    @Test
    @DisplayName("심사역은 정책 문서를 심을 수 없다 — 심으면 AI 가 그것을 권위로 인용한다")
    void reviewerCannotRegisterPolicyDocument() throws Exception {
        mockMvc.perform(post("/api/internal/advisory/documents")
                        .header("X-User-Role", REVIEWER_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "docCd":"FAKE_POLICY",
                                  "docTitle":"조작된 정책",
                                  "docCategoryCd":"CREDIT_POLICY",
                                  "docVersion":"v1.0",
                                  "effectiveStartDate":"20700101",
                                  "content":"DSR 한도는 200% 이다"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("감사 권한도 정책 문서를 심을 수 없다 — 감사는 보는 자리지 바꾸는 자리가 아니다")
    void auditorCannotRegisterPolicyDocument() throws Exception {
        mockMvc.perform(post("/api/internal/advisory/documents")
                        .header("X-User-Role", AUDITOR_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "docCd":"FAKE_POLICY_2",
                                  "docTitle":"조작된 정책",
                                  "docCategoryCd":"CREDIT_POLICY",
                                  "docVersion":"v1.0",
                                  "effectiveStartDate":"20700101",
                                  "content":"내용"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("심사역은 문서를 비활성화할 수 없다 — 끄면 근거가 조용히 사라진다")
    void reviewerCannotDeactivateDocument() throws Exception {
        mockMvc.perform(put("/api/internal/advisory/documents/{docId}/activate", 1L)
                        .header("X-User-Role", REVIEWER_ONLY)
                        .param("active", "false"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("심사역은 사례 인덱스를 다시 만들 수 없다")
    void reviewerCannotReindexCases() throws Exception {
        mockMvc.perform(post("/api/internal/advisory/index/cases")
                        .header("X-User-Role", REVIEWER_ONLY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("심사역은 문서 목록·통계를 볼 수 없다 — 감사 이상만 본다")
    void reviewerCannotListDocuments() throws Exception {
        mockMvc.perform(get("/api/internal/advisory/documents")
                        .header("X-User-Role", REVIEWER_ONLY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internal/advisory/documents/stats")
                        .header("X-User-Role", REVIEWER_ONLY))
                .andExpect(status().isForbidden());
    }

    // ── 자문 룰 ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} 은 자문 룰을 바꿀 수 없다")
    @ValueSource(strings = {REVIEWER_ONLY, AUDITOR_ONLY})
    @DisplayName("admin 이 아니면 자문 룰을 바꿀 수 없다 — 룰이 곧 경고 기준이다")
    void nonAdminCannotChangeRules(String role) throws Exception {
        mockMvc.perform(put("/api/advisory/rules/{ruleId}", 1L)
                        .header("X-User-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeYn\":false}"))
                .andExpect(status().isForbidden());
    }

    // ── 심사관 통계·감사 의견 ────────────────────────────────────────────────

    @Test
    @DisplayName("심사역은 남의 ack 통계를 볼 수 없다")
    void reviewerCannotSeeOthersStats() throws Exception {
        mockMvc.perform(get("/api/advisory/stats/reviewers/{id}", 99L)
                        .header("X-User-Role", REVIEWER_ONLY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("심사역은 편향 위험점수 상위 목록을 볼 수 없다 — 동료 평가 결과다")
    void reviewerCannotSeeRiskScoreRanking() throws Exception {
        mockMvc.perform(get("/api/advisory/audit/risk-scores/top/bias")
                        .header("X-User-Role", REVIEWER_ONLY))
                .andExpect(status().isForbidden());
    }

    // ── 열려야 하는 경우 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("감사 권한이면 조회가 열린다 — 막기만 하고 열리지 않으면 기능이 죽은 것이다")
    void auditorCanRead() throws Exception {
        mockMvc.perform(get("/api/advisory/audit/risk-scores/top/bias")
                        .header("X-User-Role", AUDITOR_ONLY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/advisory/documents")
                        .header("X-User-Role", AUDITOR_ONLY))
                .andExpect(status().isOk());
    }
}
