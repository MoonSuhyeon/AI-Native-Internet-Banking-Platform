package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.support.LoanErrorCode;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * OpenAI /v1/embeddings 어댑터.
 *
 * prod 기본 구현체 — test 프로파일에서는 StubEmbeddingClient 가 대신 로드됨.
 * 모델코드: OPENAI_3S (text-embedding-3-small, 1536 차원)
 */
@Slf4j
@Primary
@Component
@Profile("!test")
public class AdvisoryOpenAiEmbeddingClient implements EmbeddingClient {

    static final String MODEL_CD  = "OPENAI_3S";
    private static final String EMBED_PATH = "/v1/embeddings";

    private final RestClient restClient;
    private final String     model;
    private final int        dimension;
    private final int        maxAttempts;
    private final long       retryBackoffMs;

    @Nullable
    private AdvisoryMetrics advisoryMetrics;

    @Autowired(required = false)
    void setAdvisoryMetrics(AdvisoryMetrics advisoryMetrics) {
        this.advisoryMetrics = advisoryMetrics;
    }

    public AdvisoryOpenAiEmbeddingClient(RestClient.Builder builder,
                                         AdvisoryRagProperties props) {
        AdvisoryRagProperties.OpenAi openai = props.openai();

        RestClient.Builder b;
        if (openai.connectTimeoutMs() > 0 || openai.readTimeoutMs() > 0) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            if (openai.connectTimeoutMs() > 0) factory.setConnectTimeout(openai.connectTimeoutMs());
            if (openai.readTimeoutMs()    > 0) factory.setReadTimeout(openai.readTimeoutMs());
            b = builder.requestFactory(factory).baseUrl(openai.baseUrl());
        } else {
            b = builder.baseUrl(openai.baseUrl());
        }

        if (openai.apiKey() != null && !openai.apiKey().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openai.apiKey());
        }

        this.restClient     = b.build();
        this.model          = props.model();
        this.dimension      = props.dimension();
        this.maxAttempts    = openai.maxAttempts();
        this.retryBackoffMs = openai.retryBackoffMs();
    }

    @Override
    public String defaultModelCd() {
        return MODEL_CD;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        Timer.Sample sample = advisoryMetrics != null ? advisoryMetrics.startRagEmbeddingTimer() : null;
        boolean success = false;
        try {
            float[] result = doEmbed(text);
            success = true;
            return result;
        } finally {
            if (advisoryMetrics != null && sample != null) {
                advisoryMetrics.recordRagEmbeddingDuration(sample, MODEL_CD, success ? "success" : "error");
            }
        }
    }

    private float[] doEmbed(String text) {
        EmbedResponse response = callWithRetry(new EmbedRequest(model, List.of(text), dimension));
        if (response.data() == null || response.data().isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_211, "openai 응답 본문 비어 있음");
        }
        List<Double> values = response.data().get(0).embedding();
        if (values.size() != dimension) {
            throw new BusinessException(LoanErrorCode.LOAN_211,
                    "openai 응답 차원 불일치: expected=" + dimension + " actual=" + values.size());
        }
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) v[i] = values.get(i).floatValue();
        return v;
    }

    private EmbedResponse callWithRetry(EmbedRequest request) {
        Throwable lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EmbedResponse body = restClient.post()
                        .uri(EMBED_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(EmbedResponse.class);
                if (body == null) throw new BusinessException(LoanErrorCode.LOAN_211, "openai 응답 null");
                return body;
            } catch (BusinessException e) {
                throw e;
            } catch (HttpClientErrorException e) {
                // 4xx — 재시도 무의미
                throw new BusinessException(LoanErrorCode.LOAN_211,
                        "openai 4xx " + e.getStatusCode() + " — 요청 형식 점검 필요");
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastEx = e;
                log.warn("[advisory-embed] attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) sleepBackoff(attempt);
            }
        }
        throw new BusinessException(LoanErrorCode.LOAN_210,
                "openai 임베딩 " + maxAttempts + "회 재시도 실패: " + lastEx.getMessage());
    }

    private void sleepBackoff(int attempt) {
        long delay = retryBackoffMs * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(LoanErrorCode.LOAN_210, "재시도 대기 중 인터럽트");
        }
    }

    record EmbedRequest(String model, List<String> input, int dimensions) {}

    record EmbedResponse(List<Item> data) {
        record Item(int index, List<Double> embedding) {}
    }
}
