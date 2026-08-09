package com.bank.ai.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 평가셋을 검색기에 태워 집계 점수를 낸다.
 *
 * <p><b>왜 검색기를 인자로 받는가.</b> RAG 경로가 둘이고(inline·es), 앞으로 더 늘 수 있다.
 * 같은 평가셋을 같은 방식으로 태워야 두 백엔드를 비교할 수 있다 — 각자 다른 방법으로
 * 재면 숫자가 나와도 견줄 수 없다.
 *
 * <p><b>검색기를 함수로 받는 이유.</b> 실제 백엔드는 DB·Elasticsearch 가 있어야 돌지만,
 * 이 러너 자체의 동작은 그것 없이도 검증할 수 있어야 한다.
 */
public final class RetrievalEvalRunner {

    private final List<Query> queries;

    private RetrievalEvalRunner(List<Query> queries) {
        this.queries = queries;
    }

    /** 질의 하나와 그 정답들. */
    public record Query(String id, String text, Set<String> relevant) {
    }

    /**
     * 집계 결과.
     *
     * @param queryCount   평가에 쓴 질의 수. 이 값이 줄면 점수 비교가 무의미해진다 —
     *                     질의를 빼서 점수를 올릴 수 있기 때문이다.
     * @param recallAtK    정답이 상위 K 안에 있던 질의 비율
     * @param mrr          정답 순위의 역수 평균
     * @param precisionAtK 상위 K 중 정답 비율의 평균
     * @param misses       한 건도 못 찾은 질의 id — 무엇이 안 되는지 바로 보여야 고칠 수 있다
     */
    public record Result(int queryCount, double recallAtK, double mrr,
                         double precisionAtK, List<String> misses) {
    }

    public static RetrievalEvalRunner load(String resourcePath) throws IOException {
        try (InputStream in = RetrievalEvalRunner.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("평가셋을 찾을 수 없다: " + resourcePath);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            List<Query> loaded = new ArrayList<>();
            for (JsonNode q : root.path("queries")) {
                Set<String> relevant = new LinkedHashSet<>();
                q.path("relevant").forEach(r -> relevant.add(r.asText()));
                loaded.add(new Query(q.path("id").asText(), q.path("query").asText(), relevant));
            }
            return new RetrievalEvalRunner(loaded);
        }
    }

    /**
     * @param retriever 질의를 받아 문서 id 를 <b>순위 순서대로</b> 돌려준다.
     *                  순서가 틀리면 MRR 이 의미를 잃는다.
     */
    public Result run(Function<String, List<String>> retriever, int k) {
        if (queries.isEmpty()) {
            // 빈 평가셋으로 만점을 내면 "평가가 통과했다" 는 착각을 준다.
            throw new IllegalStateException("평가셋이 비어 있다 — 잴 것이 없다");
        }

        double recallSum = 0;
        double mrrSum = 0;
        double precisionSum = 0;
        List<String> misses = new ArrayList<>();

        for (Query q : queries) {
            List<String> retrieved = retriever.apply(q.text());
            double recall = RetrievalEvaluator.recallAtK(retrieved, q.relevant(), k);
            recallSum += recall;
            mrrSum += RetrievalEvaluator.reciprocalRank(retrieved, q.relevant());
            precisionSum += RetrievalEvaluator.precisionAtK(retrieved, q.relevant(), k);
            if (recall == 0.0) {
                misses.add(q.id());
            }
        }

        int n = queries.size();
        return new Result(n, recallSum / n, mrrSum / n, precisionSum / n, misses);
    }

    public int size() {
        return queries.size();
    }
}
