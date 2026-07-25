package com.bank.loan.rag.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SimilarCaseOutboxDispatcher 단위 테스트 — Phase E (E3-4).
 */
@ExtendWith(MockitoExtension.class)
class SimilarCaseOutboxDispatcherTest {

    @Mock
    private LoanReviewOutboxRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final CaseOutboxProperties props =
            new CaseOutboxProperties(true, 1, 60000, 5000, 200, "loan-review.case-indexed.v1");

    private SimilarCaseOutboxDispatcher dispatcher() {
        return new SimilarCaseOutboxDispatcher(repository, kafkaTemplate, props);
    }

    @Test
    void 발행_성공시_SENT로_전이하고_저장() {
        var outbox = LoanReviewOutbox.caseIndexed(100L, "{\"source_id\":\"rev-100\"}");
        when(repository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(anyList(), any()))
                .thenReturn(List.of(outbox));
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate).send(eq("loan-review.case-indexed.v1"), eq("rev-100"), anyString());

        dispatcher().dispatch();

        assertThat(outbox.getStatus()).isEqualTo(LoanReviewOutbox.STATUS_SENT);
        verify(repository).save(outbox);
    }

    @Test
    void 발행_실패시_FAILED로_전이하고_저장() {
        var outbox = LoanReviewOutbox.caseIndexed(100L, "{}");
        when(repository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(anyList(), any()))
                .thenReturn(List.of(outbox));
        doReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")))
                .when(kafkaTemplate).send(anyString(), anyString(), anyString());

        dispatcher().dispatch();

        assertThat(outbox.getStatus()).isEqualTo(LoanReviewOutbox.STATUS_FAILED);
        assertThat(outbox.getAttemptNo()).isEqualTo(1);
        verify(repository).save(outbox);
    }

    @Test
    void 대기_건_없으면_Kafka_미호출() {
        when(repository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(anyList(), any()))
                .thenReturn(List.of());

        dispatcher().dispatch();

        verifyNoInteractions(kafkaTemplate);
    }
}
