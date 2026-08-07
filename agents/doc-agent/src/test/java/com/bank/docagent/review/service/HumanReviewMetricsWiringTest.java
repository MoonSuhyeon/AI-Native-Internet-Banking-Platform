package com.bank.docagent.review.service;

import com.bank.docagent.observability.DocAgentMetrics;
import com.bank.docagent.retention.RetentionService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심사원 결정이 <b>실제로</b> 지표에 기록되는지 검증한다.
 *
 * <p><b>왜 따로 두는가.</b> {@link com.bank.docagent.observability.DocAgentMetricsTest} 는
 * 지표 클래스 자체만 본다. 그래서 서비스가 그 클래스를 부르지 않게 되어도 통과한다 —
 * 실제로 계측 호출을 지우고 돌려보니 전부 초록이었다.
 *
 * <p>계측에서 흔히 깨지는 곳은 지표 정의가 아니라 <b>호출을 빠뜨리는 것</b>이다.
 * 그래서 서비스를 통해 결정을 내리고 카운터가 오르는지 확인한다.
 */
class HumanReviewMetricsWiringTest {

    private MeterRegistry registry;
    private DocumentSubmissionRepository repository;
    private HumanReviewService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = mock(DocumentSubmissionRepository.class);
        RetentionService retention = mock(RetentionService.class);
        service = new HumanReviewService(repository, retention, new DocAgentMetrics(registry));
    }

    private void givenPendingSubmission(UUID id) {
        DocumentSubmission submission = DocumentSubmission.builder()
                .submissionId(id)
                .applicationId("APP-1")
                .docCode("INCOME_CERT")
                .verifyStatus(VerifyStatus.HOLD)
                .humanReviewStatus(HumanReviewStatus.PENDING)
                .build();
        when(repository.findById(any())).thenReturn(Optional.of(submission));
    }

    @Test
    @DisplayName("위조 확정 결정이 지표에 기록된다")
    void confirmedForgeryIsRecorded() {
        UUID id = UUID.randomUUID();
        givenPendingSubmission(id);

        service.decide(id, HumanReviewStatus.CONFIRMED_FORGERY, "reviewer-1");

        assertThat(registry.get("doc_agent_human_review_total")
                .tag("decision", "CONFIRMED_FORGERY").counter().count())
                .as("서비스가 DocAgentMetrics 를 부르지 않으면 여기서 걸린다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("정상 처리 결정도 기록된다 — 자동 판정을 뒤집은 경우다")
    void clearedIsRecorded() {
        UUID id = UUID.randomUUID();
        givenPendingSubmission(id);

        service.decide(id, HumanReviewStatus.CLEARED, "reviewer-1");

        assertThat(registry.get("doc_agent_human_review_total")
                .tag("decision", "CLEARED").counter().count())
                .isEqualTo(1.0);
    }
}
