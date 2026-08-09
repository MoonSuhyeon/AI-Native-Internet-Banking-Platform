package com.bank.ai.rag.eval;

import java.util.List;
import java.util.Set;

/**
 * 검색 품질 계산.
 *
 * <p><b>왜 세 지표를 함께 보는가.</b> 하나만 보면 다른 방향으로 나빠지는 것을 놓친다.
 *
 * <ul>
 *   <li><b>Recall@K</b> — 정답이 상위 K 안에 있는가. 없으면 LLM 은 근거 없이 답한다.</li>
 *   <li><b>MRR</b> — 정답이 <b>몇 번째</b>에 있는가. 심사 화면은 상위 한두 건만 보여주므로
 *       10위에 있는 정답은 사실상 없는 것과 같다. Recall 만 보면 이 차이가 안 보인다.</li>
 *   <li><b>Precision@K</b> — 가져온 것 중 쓸모 있는 비율. 낮으면 LLM 컨텍스트가
 *       무관한 문서로 채워져 "중간이 유실되는" 문제가 생긴다.</li>
 * </ul>
 *
 * <p>계산은 단순하지만 <b>정의를 코드로 못박는 것</b>이 목적이다. 사람마다 Recall 을
 * 다르게 세면 개선 여부를 비교할 수 없다.
 */
public final class RetrievalEvaluator {

    private RetrievalEvaluator() {
    }

    /**
     * 정답이 상위 K 안에 하나라도 있으면 1, 없으면 0.
     *
     * <p>정답이 여러 개일 때 "전부 찾았는가" 가 아니라 "하나라도 찾았는가" 로 센다.
     * 심사역은 근거 하나만 있어도 판단할 수 있고, 전부를 요구하면 정답을 여러 개
     * 적어 둔 질의가 부당하게 불리해진다.
     */
    public static double recallAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        return retrieved.stream().limit(k).anyMatch(relevant::contains) ? 1.0 : 0.0;
    }

    /**
     * 첫 정답의 순위 역수. 1위면 1.0, 2위면 0.5, 없으면 0.
     *
     * <p>순위를 반영하므로 Recall 이 같아도 더 위에 올린 검색기가 높게 나온다.
     */
    public static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 상위 K 중 정답 비율.
     *
     * <p>분모는 <b>실제로 가져온 개수</b>다. K=5 인데 2건만 왔다면 2로 나눈다 —
     * 적게 가져온 것을 노이즈로 벌주지 않기 위해서다.
     */
    public static double precisionAtK(List<String> retrieved, Set<String> relevant, int k) {
        List<String> top = retrieved.stream().limit(k).toList();
        if (top.isEmpty()) {
            return 0.0;
        }
        long hits = top.stream().filter(relevant::contains).count();
        return (double) hits / top.size();
    }
}
