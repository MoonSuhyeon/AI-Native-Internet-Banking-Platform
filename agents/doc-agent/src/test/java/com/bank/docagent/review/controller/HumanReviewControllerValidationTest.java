package com.bank.docagent.review.controller;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 심사 결정의 입력 검증과 <b>결정 주체</b> 검증.
 *
 * <p><b>왜 이 파일이 커졌나.</b> 원래는 {@code reviewer_id} 를 빼고 부르면 결정이 DB 에
 * 반영된 뒤 응답을 만들다 500 이 나던 것을 막는 테스트였다. 그런데 그 검증은
 * <b>값이 있는지</b>만 봤다. 누구인지는 보지 않았으므로 아무 이름이나 적으면 그대로
 * 기록됐다.
 *
 * <p>이 값이 그냥 감사 항목이 아니라는 점이 중요하다. 심사원 결정은
 * <b>AI 채택률 지표의 근거</b>다. 지표는 "자동 판정을 사람이 이만큼 뒤집었다" 고
 * 말하는데, 그 사람이 자칭이면 성능을 재는 축이 위조 가능한 입력 위에 서게 된다.
 *
 * <p>게다가 게이트웨이에 doc-agent 라우트가 아예 없어서, 8087 을 직접 부르면
 * 그만이었다. {@code SecurityConfig} 주석은 "gateway 뒤에 위치하므로 자체 인증 불필요"
 * 라고 말하고 있었지만 그 게이트웨이 경로가 존재하지 않았다.
 *
 * <p>여기서 보는 것은 두 가지다 — 거절된 요청이 <b>아무것도 바꾸지 않는가</b>,
 * 그리고 기록되는 심사원이 <b>게이트웨이가 검증한 사람인가</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HumanReviewControllerValidationTest {

    /** application-test.yml 의 doc-agent.gateway.shared-secret 과 같아야 한다. */
    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final String REVIEWER = "EMP001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentSubmissionRepository repository;

    private UUID givenPendingHold() {
        DocumentSubmission saved = repository.save(DocumentSubmission.builder()
                .applicationId("APP-VALID-" + UUID.randomUUID().toString().substring(0, 8))
                .docCode("INCOME")
                .verifyStatus(VerifyStatus.HOLD)
                .humanReviewStatus(HumanReviewStatus.PENDING)
                .build());
        return saved.getSubmissionId();
    }

    /** 게이트웨이가 직원 토큰을 검증해 통과시킨 상태. */
    private static MockHttpServletRequestBuilder viaGateway(
            MockHttpServletRequestBuilder builder, String employeeId) {
        return builder
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-Employee-Id", employeeId);
    }

    private void assertUntouched(UUID id) {
        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.getHumanReviewStatus())
                .as("거절된 요청이 상태를 바꿔서는 안 된다")
                .isEqualTo(HumanReviewStatus.PENDING);
        assertThat(after.getReviewerId())
                .as("심사원 없는 결정이 남으면 감사 추적이 끊긴다")
                .isNull();
    }

    // ── 결정 주체 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("게이트웨이를 거치지 않으면 결정할 수 없고 상태도 그대로다")
    void withoutGatewayIdentityIsRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\"}"))
                .andExpect(status().isForbidden());

        assertUntouched(id);
    }

    @Test
    @DisplayName("신원 헤더를 손으로 붙여도 통하지 않는다 — 게이트웨이 증거가 없으면 믿지 않는다")
    void forgedIdentityHeaderIsRejected() throws Exception {
        // 8087 이 브라우저에 열려 있는 한, 헤더만 신뢰하면 위조 방식이
        // "body 에 적기" 에서 "헤더에 적기" 로 바뀔 뿐이다.
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .header("X-Employee-Id", "EMP999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\"}"))
                .andExpect(status().isForbidden());

        assertUntouched(id);
    }

    @Test
    @DisplayName("시크릿이 틀리면 거절한다")
    void wrongSecretIsRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .header("X-Gateway-Auth", GATEWAY_SECRET + "x")
                        .header("X-Employee-Id", REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CLEARED\"}"))
                .andExpect(status().isForbidden());

        assertUntouched(id);
    }

    @Test
    @DisplayName("고객 토큰이면 거절한다 — 게이트웨이는 빈 직원 ID 를 붙인다")
    void customerTokenIsRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("X-Employee-Id", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CLEARED\"}"))
                .andExpect(status().isForbidden());

        assertUntouched(id);
    }

    @Test
    @DisplayName("body 로 심사원을 넣어도 무시된다 — 기록은 검증된 신원으로 남는다")
    void bodyCannotOverrideReviewer() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(viaGateway(post("/api/documents/{id}/review", id), REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\",\"reviewer_id\":\"EMP999\"}"))
                .andExpect(status().isOk());

        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.getReviewerId())
                .as("body 값이 채택되면 위조가 '남의 이름으로' 확정된다")
                .isEqualTo(REVIEWER);
    }

    // ── 입력 검증 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("decision 이 없으면 400 이고 상태는 그대로다")
    void missingDecisionRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(viaGateway(post("/api/documents/{id}/review", id), REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertUntouched(id);
    }

    @Test
    @DisplayName("정상 요청은 200 이고 검증된 심사원이 기록된다")
    void validRequestSucceeds() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(viaGateway(post("/api/documents/{id}/review", id), REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\"}"))
                .andExpect(status().isOk());

        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.getHumanReviewStatus()).isEqualTo(HumanReviewStatus.CONFIRMED_FORGERY);
        assertThat(after.getReviewerId()).isEqualTo(REVIEWER);
    }

    // ── 나머지 직원 전용 경로 ────────────────────────────────────────────────

    @Test
    @DisplayName("심사 대기 목록도 직원만 볼 수 있다 — 고객 서류와 위조 점수가 나온다")
    void queueRequiresEmployee() throws Exception {
        mockMvc.perform(get("/api/documents/queue"))
                .andExpect(status().isForbidden());

        mockMvc.perform(viaGateway(get("/api/documents/queue"), REVIEWER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("법적보존 해제는 직원만 할 수 있다 — 끄면 증거가 삭제 대상이 된다")
    void legalHoldRequiresEmployee() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(patch("/api/documents/{id}/legal-hold/disable", id))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/documents/{id}/legal-hold/enable", id))
                .andExpect(status().isForbidden());

        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.isLegalHold())
                .as("거절된 요청이 보존 상태를 바꿔서는 안 된다")
                .isFalse();
    }
}
