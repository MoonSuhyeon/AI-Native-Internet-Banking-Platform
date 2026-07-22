package com.bank.ai.rag.enricher;

import com.bank.ai.rag.embedding.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CaseEmbeddingEnricher 단위 테스트 — Phase E (E3-6).
 */
@ExtendWith(MockitoExtension.class)
class CaseEmbeddingEnricherTest {

    @Mock EmbeddingClient embeddingClient;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock Acknowledgment ack;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CaseEnricherProperties PROPS =
            new CaseEnricherProperties(true,
                    "loan-review.case-indexed.v1",
                    "loan-review.case-indexed-enriched.v1");

    private CaseEmbeddingEnricher enricher() {
        return new CaseEmbeddingEnricher(embeddingClient, kafkaTemplate, MAPPER, PROPS);
    }

    @Test
    void 임베딩_추가_후_enriched_토픽에_발행() throws Exception {
        String raw = """
                {"corpus":"similar_cases","source_id":"rev-1","chunk_seq":0,
                 "chunk_text":"테스트 청크","metadata":{"decision":"APPROVE"}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("loan-review.case-indexed.v1", 0, 0L, "rev-1", raw);

        float[] vec = new float[768];  // all-zero stub vector
        when(embeddingClient.embed("테스트 청크")).thenReturn(vec);
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate).send(anyString(), anyString(), anyString());

        enricher().enrich(record, ack);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("loan-review.case-indexed-enriched.v1"),
                eq("rev-1"),
                bodyCaptor.capture());

        String enriched = bodyCaptor.getValue();
        assertThat(enriched).contains("\"embedding\"");
        assertThat(enriched).contains("\"embedding_model\":\"text-embedding-005\"");
        assertThat(enriched).contains("\"created_at\"");
        assertThat(enriched).contains("\"corpus\":\"similar_cases\"");
        verify(ack).acknowledge();
    }

    @Test
    void 임베딩_실패시_ACK하고_skip() {
        String raw = """
                {"corpus":"similar_cases","source_id":"rev-2","chunk_seq":0,
                 "chunk_text":"텍스트","metadata":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("loan-review.case-indexed.v1", 0, 1L, "rev-2", raw);
        when(embeddingClient.embed(any())).thenThrow(new RuntimeException("embedding API down"));

        enricher().enrich(record, ack);

        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void chunk_text_없으면_임베딩_미호출_후_ACK() {
        String raw = """
                {"corpus":"similar_cases","source_id":"rev-3","chunk_seq":0,"metadata":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("loan-review.case-indexed.v1", 0, 2L, "rev-3", raw);

        enricher().enrich(record, ack);

        verifyNoInteractions(embeddingClient, kafkaTemplate);
        verify(ack).acknowledge();
    }
}
