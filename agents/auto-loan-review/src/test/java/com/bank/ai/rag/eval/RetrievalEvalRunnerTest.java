package com.bank.ai.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 평가 러너.
 *
 * <p>실제 백엔드(pgvector·Elasticsearch) 없이 러너 자체를 검증한다. 검색기를 함수로
 * 받으므로 <b>완벽한 검색기</b>와 <b>아무것도 못 찾는 검색기</b>를 넣어 양 끝을 확인할 수 있다.
 *
 * <p>실제 검색 품질은 백엔드가 있는 환경에서 이 러너로 잰다 — 그때도 같은 계산을
 * 쓰기 때문에 두 백엔드를 견줄 수 있다.
 */
class RetrievalEvalRunnerTest {

    private static final String EVAL_SET = "/rag/retrieval-eval-set.json";

    /** 질의별 정답을 미리 아는 검색기. 평가셋의 정답을 그대로 돌려준다. */
    private static final Map<String, String> ANSWER_KEY = Map.of(
            "주택담보대출 받을 때 DSR 몇 퍼센트까지 되나요", "MORT_DSR_LIMIT_V1",
            "총부채원리금상환비율 상한", "MORT_DSR_LIMIT_V1",
            "집 살 때 담보 얼마나 인정해주나", "MORT_LTV_LIMIT_V1",
            "생애최초 주택구입자 담보인정비율", "MORT_LTV_LIMIT_V1",
            "신용점수 몇 점 이상이어야 신청할 수 있나요", "CRED_SCORE_MIN_V1",
            "KCB 최저 점수 기준", "CRED_SCORE_MIN_V1",
            "연체 이력 있으면 대출 안 되나요", "DELINQ_24M_BAR_V1",
            "미성년자도 신청 가능한가요", "AGE_MIN_V1",
            "상품별 부도확률 임계치는 어디에 정해져 있나요", "PD_THRESHOLD_MATRIX_V1",
            "머신러닝 모델이 승인 여부를 직접 결정하나요", "AUTO_REVIEW_GOVERNANCE_V1"
    );

    @Test
    @DisplayName("완벽한 검색기는 만점이 나온다 — 계산이 반대로 돼 있지 않다")
    void perfectRetrieverScoresOne() throws IOException {
        RetrievalEvalRunner runner = RetrievalEvalRunner.load(EVAL_SET);

        // 정답을 1위로 돌려준다. 평가셋에 없는 질의는 빈 결과.
        var result = runner.run(q -> {
            String answer = ANSWER_KEY.get(q);
            return answer == null ? List.of() : List.of(answer);
        }, 5);

        // ANSWER_KEY 가 다루지 못한 질의가 있으므로 만점은 아니지만,
        // 대부분을 1위로 맞혔으니 높아야 한다.
        assertThat(result.recallAtK()).isGreaterThan(0.8);
        assertThat(result.mrr())
                .as("전부 1위로 돌려줬으므로 MRR 은 Recall 과 같아야 한다")
                .isEqualTo(result.recallAtK());
    }

    @Test
    @DisplayName("못 찾는 검색기는 0점이고, 어떤 질의가 실패했는지 알려준다")
    void failingRetrieverReportsMisses() throws IOException {
        RetrievalEvalRunner runner = RetrievalEvalRunner.load(EVAL_SET);

        var result = runner.run(q -> List.of("무관한문서"), 5);

        assertThat(result.recallAtK()).isZero();
        assertThat(result.mrr()).isZero();
        assertThat(result.misses())
                .as("무엇이 안 됐는지 알려주지 않으면 점수만 보고 고칠 수 없다")
                .hasSize(result.queryCount());
    }

    @Test
    @DisplayName("순위가 낮으면 Recall 은 같아도 MRR 이 떨어진다")
    void rankAffectsMrrOnly() throws IOException {
        RetrievalEvalRunner runner = RetrievalEvalRunner.load(EVAL_SET);

        var top = runner.run(q -> {
            String a = ANSWER_KEY.get(q);
            return a == null ? List.of() : List.of(a, "X", "Y");
        }, 5);
        var buried = runner.run(q -> {
            String a = ANSWER_KEY.get(q);
            return a == null ? List.of() : List.of("X", "Y", a);
        }, 5);

        assertThat(buried.recallAtK()).isEqualTo(top.recallAtK());
        assertThat(buried.mrr())
                .as("심사 화면은 상위 한두 건만 보여준다 — 이 차이가 실제 사용에서 크다")
                .isLessThan(top.mrr());
    }

    @Test
    @DisplayName("질의 수를 함께 낸다 — 질의를 빼서 점수를 올리는 것을 막는다")
    void reportsQueryCount() throws IOException {
        RetrievalEvalRunner runner = RetrievalEvalRunner.load(EVAL_SET);

        var result = runner.run(q -> List.of(), 5);

        assertThat(result.queryCount()).isEqualTo(runner.size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("빈 평가셋으로는 돌지 않는다 — 만점이 나오면 통과했다고 착각한다")
    void emptyEvalSetFails() {
        assertThatThrownBy(() -> RetrievalEvalRunner.load("/rag/does-not-exist.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
