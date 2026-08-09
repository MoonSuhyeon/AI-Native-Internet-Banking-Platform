package com.bank.ai.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 로컬 Ollama 임베딩 클라이언트.
 *
 * <p><b>왜 필요한가.</b> 기본값인 {@link StubEmbeddingClient} 는 SHA-256 해시라
 * 의미를 전혀 모른다 — 한 글자만 달라도 벡터가 완전히 달라진다. 그것으로는 벡터 검색이
 * 잡음이 되고, 사실상 BM25(단어 겹침)만 재게 된다. 그런데 검색 품질을 재는 평가셋은
 * 일부러 단어가 겹치지 않는 질의로 만들었으므로, 스텁으로 재면 숫자를 보고
 * "검색이 나쁘다" 고 오진하게 된다.
 *
 * <p>Vertex AI({@link SpringAiEmbeddingClient})는 GCP 인증이 필요하다. 로컬·CI 에서
 * 키 없이 <b>진짜 임베딩</b>을 쓰려면 이 경로가 있어야 한다.
 *
 * <p><b>모델 선택.</b> 한국어 정책 문서를 다루므로 다국어 모델이어야 한다.
 * 측정해 보니 {@code nomic-embed-text} 는 한국어에서 정답과 오답의 간격이
 * 0.663 대 0.662 로 사실상 구분하지 못했고, {@code bge-m3} 는 0.608 대 0.566 으로
 * 벌어졌다. 그래서 기본값을 bge-m3 로 둔다. <b>차원이 1024 라 768 을 전제한 설정·색인과
 * 맞지 않으니</b>, 모델을 바꾸면 {@code ai.rag.embedding.dimensions} 와 ES 매핑의
 * {@code dims} 도 함께 바꿔야 한다.
 *
 * <p>모델이 없으면 Ollama 가 404 를 준다. 그 경우 예외를 던진다 — 조용히 0 벡터를
 * 돌려주면 검색이 아무거나 반환하고 아무도 눈치채지 못한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final String model;

    public OllamaEmbeddingClient(
            RestClient.Builder builder,
            @Value("${ai.rag.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ai.rag.embedding.ollama.model:bge-m3}") String model,
            @Value("${ai.rag.embedding.ollama.timeout-ms:60000}") int timeoutMs) {

        this.model = model;

        // 임베딩은 모델 첫 로딩이 느리다. 기본 타임아웃(무제한)에 기대면 매달리고,
        // 너무 짧으면 첫 호출마다 실패한다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        log.info("임베딩 제공자=ollama model={} baseUrl={}", model, baseUrl);
    }

    @Override
    public float[] embed(String text) {
        EmbeddingResponse res = restClient.post()
                .uri("/api/embeddings")
                .body(Map.of("model", model, "prompt", text))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (res == null || res.embedding() == null || res.embedding().isEmpty()) {
            // 0 벡터를 돌려주면 모든 문서와 똑같이 가까워져 검색이 아무거나 반환한다.
            // 그 상태는 지표로도 안 잡히므로 여기서 끊는다.
            throw new IllegalStateException(
                    "Ollama 임베딩 응답이 비었다. 모델이 설치돼 있는지 확인할 것: " + model);
        }

        float[] vec = new float[res.embedding().size()];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = res.embedding().get(i).floatValue();
        }
        return vec;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        // Ollama 의 /api/embeddings 는 한 번에 하나만 받는다. 배치 API 가 생기면 바꾼다.
        return texts.stream().map(this::embed).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<Double> embedding) {
    }
}
