package com.bank.ai.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 검색 품질 계산의 정의를 못박는다.
 *
 * <p><b>왜 계산식까지 테스트하는가.</b> 이 숫자로 "검색이 좋아졌다/나빠졌다" 를 판단한다.
 * 계산이 틀리면 <b>그 위의 모든 결론이 틀리는데</b>, 숫자는 그럴듯하게 나오므로
 * 아무도 의심하지 않는다. 잘못된 지표는 지표가 없는 것보다 나쁘다.
 */
class RetrievalEvaluatorTest {

    private static final Set<String> RELEVANT = Set.of("DOC-A");

    @Nested
    @DisplayName("Recall@K — 상위 K 안에 정답이 있는가")
    class Recall {

        @Test
        @DisplayName("K 안에 있으면 1, 밖이면 0")
        void hitInsideWindowOnly() {
            List<String> retrieved = List.of("X", "Y", "DOC-A");

            assertThat(RetrievalEvaluator.recallAtK(retrieved, RELEVANT, 3)).isEqualTo(1.0);
            assertThat(RetrievalEvaluator.recallAtK(retrieved, RELEVANT, 2))
                    .as("3위에 있는 정답은 K=2 에서 못 찾은 것이다")
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("정답이 여러 개면 하나만 찾아도 성공으로 센다")
        void anyRelevantCounts() {
            // 전부를 요구하면 정답을 넉넉히 적어 둔 질의가 부당하게 불리해진다.
            // 심사역은 근거 하나만 있어도 판단할 수 있다.
            Set<String> many = Set.of("DOC-A", "DOC-B", "DOC-C");

            assertThat(RetrievalEvaluator.recallAtK(List.of("DOC-B", "X"), many, 5)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("정답이 정의되지 않은 질의는 0 — 조용히 만점을 주지 않는다")
        void emptyRelevantIsZero() {
            // 평가셋에 정답을 빠뜨리면 그 질의는 항상 통과하게 되어,
            // 평가가 느슨해진 것을 아무도 모른다.
            assertThat(RetrievalEvaluator.recallAtK(List.of("X"), Set.of(), 5)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("MRR — 정답이 몇 번째인가")
    class Mrr {

        @Test
        @DisplayName("순위가 낮을수록 값이 작아진다")
        void rankMatters() {
            assertThat(RetrievalEvaluator.reciprocalRank(List.of("DOC-A", "X"), RELEVANT))
                    .isEqualTo(1.0);
            assertThat(RetrievalEvaluator.reciprocalRank(List.of("X", "DOC-A"), RELEVANT))
                    .isEqualTo(0.5);
            assertThat(RetrievalEvaluator.reciprocalRank(List.of("X", "Y", "DOC-A"), RELEVANT))
                    .isCloseTo(0.333, within(0.001));
        }

        @Test
        @DisplayName("Recall 이 같아도 순위가 다르면 갈린다 — 이게 MRR 을 두는 이유다")
        void sameRecallDifferentMrr() {
            List<String> good = List.of("DOC-A", "X", "Y");
            List<String> bad = List.of("X", "Y", "DOC-A");

            assertThat(RetrievalEvaluator.recallAtK(good, RELEVANT, 3))
                    .isEqualTo(RetrievalEvaluator.recallAtK(bad, RELEVANT, 3));
            assertThat(RetrievalEvaluator.reciprocalRank(good, RELEVANT))
                    .as("심사 화면은 상위 한두 건만 보여준다 — 3위 정답은 사실상 없는 것과 같다")
                    .isGreaterThan(RetrievalEvaluator.reciprocalRank(bad, RELEVANT));
        }

        @Test
        @DisplayName("못 찾으면 0")
        void missIsZero() {
            assertThat(RetrievalEvaluator.reciprocalRank(List.of("X", "Y"), RELEVANT)).isZero();
        }
    }

    @Nested
    @DisplayName("Precision@K — 가져온 것 중 쓸모 있는 비율")
    class Precision {

        @Test
        @DisplayName("노이즈가 많으면 낮아진다")
        void noiseLowersPrecision() {
            assertThat(RetrievalEvaluator.precisionAtK(List.of("DOC-A", "X", "Y", "Z"), RELEVANT, 4))
                    .isEqualTo(0.25);
        }

        @Test
        @DisplayName("적게 가져온 것을 노이즈로 벌주지 않는다")
        void divisorIsActualSize() {
            // K=5 인데 1건만 왔고 그게 정답이면 1.0 이다. 5로 나누면
            // "적게 가져오는 정확한 검색기" 가 부당하게 낮게 나온다.
            assertThat(RetrievalEvaluator.precisionAtK(List.of("DOC-A"), RELEVANT, 5))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("빈 결과는 0")
        void emptyIsZero() {
            assertThat(RetrievalEvaluator.precisionAtK(List.of(), RELEVANT, 5)).isZero();
        }
    }
}
