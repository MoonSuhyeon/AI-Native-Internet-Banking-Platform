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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 심사 결정 요청의 입력 검증.
 *
 * <p><b>왜 필요한가.</b> {@code reviewer_id} 를 빼고 호출하면 결정이 DB 에 반영된 뒤
 * 응답을 만들다 NPE 로 500 이 났다({@code Map.of} 는 null 을 거부한다). 호출자는
 * 실패로 보는데 상태는 이미 바뀌어 있고, <b>위조를 누가 확정했는지 감사 기록이 빈다.</b>
 *
 * <p>HOLD 건 확정은 사람이 책임지는 행위라 심사원 없는 결정은 남으면 안 된다.
 * 그래서 상태를 건드리기 전에 400 으로 막는다.
 *
 * <p>핵심은 응답 코드가 아니라 <b>아무것도 바뀌지 않았는가</b>이다. 400 만 확인하면
 * "거절했지만 이미 반영된" 경우를 놓친다 — 원래 결함이 정확히 그 모양이었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HumanReviewControllerValidationTest {

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

    private void assertUntouched(UUID id) {
        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.getHumanReviewStatus())
                .as("거절된 요청이 상태를 바꿔서는 안 된다")
                .isEqualTo(HumanReviewStatus.PENDING);
        assertThat(after.getReviewerId())
                .as("심사원 없는 결정이 남으면 감사 추적이 끊긴다")
                .isNull();
    }

    @Test
    @DisplayName("reviewer_id 가 없으면 400 이고 상태는 그대로다")
    void missingReviewerIdRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\"}"))
                .andExpect(status().isBadRequest());

        assertUntouched(id);
    }

    @Test
    @DisplayName("reviewer_id 가 공백뿐이어도 거절한다")
    void blankReviewerIdRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CLEARED\",\"reviewer_id\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertUntouched(id);
    }

    @Test
    @DisplayName("decision 이 없으면 400 이고 상태는 그대로다")
    void missingDecisionRejected() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer_id\":\"reviewer-1\"}"))
                .andExpect(status().isBadRequest());

        assertUntouched(id);
    }

    @Test
    @DisplayName("정상 요청은 200 이고 심사원이 기록된다")
    void validRequestSucceeds() throws Exception {
        UUID id = givenPendingHold();

        mockMvc.perform(post("/api/documents/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_FORGERY\",\"reviewer_id\":\"reviewer-1\"}"))
                .andExpect(status().isOk());

        DocumentSubmission after = repository.findById(id).orElseThrow();
        assertThat(after.getHumanReviewStatus()).isEqualTo(HumanReviewStatus.CONFIRMED_FORGERY);
        assertThat(after.getReviewerId()).isEqualTo("reviewer-1");
    }
}
