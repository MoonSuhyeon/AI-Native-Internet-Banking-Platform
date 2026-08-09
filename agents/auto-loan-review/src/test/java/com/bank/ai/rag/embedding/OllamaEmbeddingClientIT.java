package com.bank.ai.rag.embedding;

import com.bank.ai.rag.eval.RetrievalEvalRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 임베딩으로 검색 품질을 잰다.
 *
 * <p><b>왜 이 테스트가 필요한가.</b> 평가셋과 계산은 준비돼 있었지만 실제 임베딩으로
 * 돌려 본 적이 없었다. 기본 제공자인 스텁은 SHA-256 해시라 의미를 모르므로,
 * 그것으로 잰 숫자는 <b>벡터 검색이 아니라 단어 겹침</b>을 잰 것이다.
 *
 * <p>여기서는 코퍼스를 벡터로 만들어 두고 질의와 코사인 유사도로 순위를 매긴다.
 * Elasticsearch 없이 임베딩 품질 자체만 본다 — BM25 와 섞이면 무엇이 기여했는지
 * 알 수 없기 때문이다.
 *
 * <p><b>Ollama 가 없으면 건너뛴다.</b> 다만 조용히 통과시키지 않고 이유를 남긴다 —
 * "돌았는데 통과" 와 "안 돌았다" 가 구별되지 않으면 품질 저하를 놓친다.
 *
 * <p>기준선은 낮게 잡았다. 이 테스트의 목적은 "얼마나 좋은가" 가 아니라
 * <b>"임베딩이 실제로 의미를 잡고 있는가"</b> 이고, 모델을 바꿀 때 크게 나빠지면
 * 걸리게 하는 것이다.
 */
class OllamaEmbeddingClientIT {

    private static final String OLLAMA = System.getenv().getOrDefault(
            "OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = System.getenv().getOrDefault(
            "AI_RAG_EMB_OLLAMA_MODEL", "bge-m3");

    /** 평가셋의 정답 문서 id → 실제 정책 문장. application.yml 의 policy.inline 과 같다. */
    private static final Map<String, String> CORPUS = new LinkedHashMap<>();

    static {
        CORPUS.put("MORT_DSR_LIMIT_V1", "주담대 DSR 한도는 자행 신용정책서 §3.1.2 에 따라 40% 이하.");
        CORPUS.put("MORT_LTV_LIMIT_V1", "주담대 LTV 한도는 70% (생애최초 80%).");
        CORPUS.put("CRED_SCORE_MIN_V1", "자행 정책 최저 신용점수: NICE 600, KCB 600.");
        CORPUS.put("DELINQ_24M_BAR_V1", "24개월 내 진행중 연체 1건 이상 시 즉시 반려 (신용정보법 §32 운영 기준).");
        CORPUS.put("AGE_MIN_V1", "신청 자격 최소 연령 19세 (민법 성인 기준).");
        CORPUS.put("PD_THRESHOLD_MATRIX_V1", "상품·세그먼트별 PD 임계치는 신용정책위원회 분기 의결의 정책 매트릭스에 따른다.");
        CORPUS.put("DECISION_CONFIDENCE_GUIDANCE_V1", "decision 모델 신뢰도 ≥ 0.95 ∧ PD 안전여유 이하 시 강한 자동 승인 권고.");
        CORPUS.put("AUTO_REVIEW_GOVERNANCE_V1", "ML 모델은 변별력 산출만 수행 — 모든 의사결정은 정책 매트릭스 + Rule Engine 결정론 로직.");
    }

    private static boolean ollamaAvailable;

    @BeforeAll
    static void probe() {
        ollamaAvailable = reachable(OLLAMA + "/api/tags");
        if (!ollamaAvailable) {
            System.out.println("[검색품질] Ollama 없음 — 건너뜀. "
                    + "실제 임베딩 품질은 측정되지 않았다. baseUrl=" + OLLAMA);
        }
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private OllamaEmbeddingClient client() {
        return new OllamaEmbeddingClient(RestClient.builder(), OLLAMA, MODEL, 120_000);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Test
    @DisplayName("실제 임베딩으로 평가셋을 돌려 Recall@3 기준선을 지킨다")
    void retrievalQualityMeetsBaseline() throws IOException {
        assumeTrue(ollamaAvailable, "Ollama 가 없어 실제 임베딩 품질을 재지 못했다");

        OllamaEmbeddingClient embeddings = client();

        Map<String, float[]> indexed = new LinkedHashMap<>();
        CORPUS.forEach((id, text) -> indexed.put(id, embeddings.embed(text)));

        RetrievalEvalRunner runner =
                RetrievalEvalRunner.load("/rag/retrieval-eval-set.json");

        var result = runner.run(query -> {
            float[] q = embeddings.embed(query);
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            indexed.forEach((id, vec) -> scored.add(Map.entry(id, cosine(q, vec))));
            scored.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
            return scored.stream().map(Map.Entry::getKey).toList();
        }, 3);

        System.out.printf("[검색품질] model=%s queries=%d Recall@3=%.3f MRR=%.3f 실패=%s%n",
                MODEL, result.queryCount(), result.recallAtK(), result.mrr(), result.misses());

        // 기준선은 "의미를 잡고 있는가" 를 보는 선이다. 스텁(해시)이면 우연 수준으로 떨어진다.
        assertThat(result.recallAtK())
                .as("실제 임베딩이면 대부분의 질의가 상위 3 안에 정답을 넣어야 한다. "
                    + "크게 낮으면 모델이 한국어를 못 잡거나 스텁이 끼어든 것이다. 실패: %s",
                    result.misses())
                .isGreaterThanOrEqualTo(0.7);
    }

    @Test
    @DisplayName("의미가 가까운 문서가 먼 문서보다 높게 나온다 — 해시 임베딩이면 여기서 걸린다")
    void semanticallyCloserDocumentScoresHigher() {
        assumeTrue(ollamaAvailable, "Ollama 가 없어 실제 임베딩 품질을 재지 못했다");

        OllamaEmbeddingClient embeddings = client();
        float[] query = embeddings.embed("집 살 때 담보 얼마나 인정해주나");

        double ltv = cosine(query, embeddings.embed(CORPUS.get("MORT_LTV_LIMIT_V1")));
        double delinq = cosine(query, embeddings.embed(CORPUS.get("DELINQ_24M_BAR_V1")));

        assertThat(ltv)
                .as("단어가 하나도 겹치지 않아도 의미로 찾아야 한다. "
                    + "해시 기반 스텁이면 이 관계가 성립하지 않는다")
                .isGreaterThan(delinq);
    }

    @Test
    @DisplayName("차원이 설정과 맞는다 — 어긋나면 색인된 벡터와 질의가 맞지 않아 검색이 실패한다")
    void dimensionMatchesConfiguration() {
        assumeTrue(ollamaAvailable, "Ollama 가 없어 실제 임베딩 품질을 재지 못했다");

        float[] vec = client().embed("차원 확인");

        // bge-m3 = 1024, nomic-embed-text·text-embedding-005 = 768.
        // 모델을 바꾸면 application.yml 의 dimensions 와 ES 매핑의 dims 도 바꿔야 한다.
        assertThat(vec.length)
                .as("모델 %s 의 차원. 설정(ai.rag.embedding.dimensions)·ES 매핑과 같아야 한다", MODEL)
                .isIn(768, 1024);
    }
}
