package com.bank.ai.rag.enricher;

import com.bank.ai.rag.embedding.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 케이스 임베딩 엔리처 — Phase E (E3-6).
 *
 * <p>loan-service outbox dispatcher 가 발행한 raw 케이스 청크(임베딩 없음)를
 * {@code loan-review.case-indexed.v1} 에서 소비해 {@link EmbeddingClient} 로 벡터를 생성 후
 * {@code loan-review.case-indexed-enriched.v1} 에 재발행. Kafka Connect ES sink 가 후자를 소비해
 * {@code kb_similar_cases_v1} 인덱스에 색인한다.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>raw JSON 파싱 → {@code chunk_text} 추출</li>
 *   <li>{@link EmbeddingClient#embed} 호출 → 768d float 벡터 획득</li>
 *   <li>enriched 문서 조립: 원본 필드 + {@code embedding / embedding_model / created_at}</li>
 *   <li>enriched topic 에 동일 Kafka key 로 발행 (key = {@code rev-{aggregateId}})</li>
 *   <li>MANUAL_IMMEDIATE ACK — Kafka offset 커밋</li>
 * </ol>
 *
 * <p>임베딩 실패 · Kafka 발행 실패는 ERROR 로그 후 ACK (재처리는 outbox 재발행으로 커버).
 * InterruptedException 시 interrupt 플래그 복원 후 ACK 없이 반환 → 재기동 시 재처리.
 *
 * <p>{@code ai.case-enricher.enabled=true} 시에만 활성.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.case-enricher", name = "enabled", havingValue = "true")
public class CaseEmbeddingEnricher {

    static final String EMBEDDING_MODEL = "text-embedding-005";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EmbeddingClient embeddingClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CaseEnricherProperties props;

    public CaseEmbeddingEnricher(
            EmbeddingClient embeddingClient,
            @Qualifier("caseEnricherKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CaseEnricherProperties props) {
        this.embeddingClient = embeddingClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @KafkaListener(
            topics = "${ai.case-enricher.raw-topic:loan-review.case-indexed.v1}",
            containerFactory = "caseEnricherListenerContainerFactory"
    )
    public void enrich(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String key = record.key();
        log.debug("[case-enricher] 수신 key={} partition={} offset={}",
                key, record.partition(), record.offset());

        try {
            Map<String, Object> doc = objectMapper.readValue(record.value(), MAP_TYPE);

            String chunkText = (String) doc.get("chunk_text");
            if (chunkText == null || chunkText.isBlank()) {
                log.warn("[case-enricher] chunk_text 누락 key={} — skip", key);
                ack.acknowledge();
                return;
            }

            float[] vec = embeddingClient.embed(chunkText);
            List<Float> embedding = toFloatList(vec);

            Map<String, Object> enriched = new LinkedHashMap<>(doc);
            enriched.put("embedding", embedding);
            enriched.put("embedding_model", EMBEDDING_MODEL);
            enriched.put("created_at", Instant.now().toString());

            String enrichedJson = objectMapper.writeValueAsString(enriched);
            kafkaTemplate.send(props.enrichedTopic(), key, enrichedJson).get();

            log.info("[case-enricher] 완료 key={} dims={}", key, vec.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[case-enricher] 인터럽트 key={} — 재기동 시 재처리", key, e);
            return;  // ACK 없이 반환 — offset 미커밋
        } catch (Exception e) {
            log.error("[case-enricher] 처리 실패 key={} — skip (outbox 재발행으로 재처리 가능)", key, e);
        }

        ack.acknowledge();
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }
}
