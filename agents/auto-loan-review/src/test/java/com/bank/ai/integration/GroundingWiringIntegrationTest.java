package com.bank.ai.integration;

import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.llm.report.GroundingValidator;
import com.bank.ai.llm.report.ReviewReport;
import com.bank.ai.rag.search.RagSearchBackend;
import com.bank.ai.rule.domain.Track;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 근거 검증 배선 통합 테스트 — docs/decisions/agent-harness-consolidation.md 4단계.
 *
 * <p>기존 {@code GroundingValidatorTest} 는 인덱스를 직접 만들어 넣는 단위 테스트라
 * <b>배선이 끊겨도 통과한다.</b> 여기서는 실제 Spring 컨텍스트가 올린 빈으로,
 * {@code application.yml} 에 실제로 적힌 정책 id 를 인용해 확인한다.
 * 검증기를 하네스로 쪼갤 때 깨질 수 있는 지점이 아래 셋이라 이것을 먼저 붙였다.
 *
 * <ul>
 *   <li>prefix 없는 id 와 {@code inline:} 이 같은 인덱스로 가는가</li>
 *   <li>RAG 가 꺼진 환경에서 {@code rag:} 인용이 통과하지 못하는가 (열려 있으면 환각이 샌다)</li>
 *   <li>RAG 가 켜지면 {@code rag:} 가 실제 RAG 빈으로 가는가</li>
 * </ul>
 */
class GroundingWiringIntegrationTest {

    // 기동 스터빙(H2·Redis 제외)은 애노테이션 인자라 상수로 뽑을 수 없어 아래 두 곳에 그대로 적는다.
    // 조합 자체는 AutoReviewPipelineSmokeTest 와 같다.

    /** application.yml {@code ai.policy.inline} 에 실제로 있는 id. 값이 바뀌면 이 테스트가 먼저 알린다. */
    private static final String REAL_POLICY_ID = "MORT_DSR_LIMIT_V1";
    private static final String OTHER_REAL_POLICY_ID = "MORT_LTV_LIMIT_V1";

    private static ReviewReport reportWith(Track track, List<String> citationIds) {
        var citations = citationIds.stream()
                .map(id -> new ReviewReport.Citation(id, "src", "text"))
                .toList();
        return new ReviewReport(track, "본문", List.of(), List.of(), "권고", citations, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 기본 배선 — RAG 꺼짐 (운영 기본값)
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "ai.llm.provider=stub",
            "spring.datasource.url=jdbc:h2:mem:grounddb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.locations=classpath:db/h2-migration",
            "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                    "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration"
    })
    class RagOff {

        @Autowired
        private GroundingValidator validator;

        @Autowired
        private ApplicationContext context;

        @Autowired
        private PolicyIndex policyIndex;

        @Test
        void 컨텍스트가_인라인_인덱스만_올린다() {
            assertThat(context.getBeanNamesForType(com.bank.ai.rag.policy.RagPolicyIndex.class))
                    .as("ai.rag.enabled 기본값(false) 에서는 RAG 인덱스가 없어야 한다")
                    .isEmpty();
            assertThat(policyIndex.exists(REAL_POLICY_ID))
                    .as("application.yml 의 정책 id 가 인덱스에 실제로 바인딩됐는가")
                    .isTrue();
        }

        @Test
        void 실제_정책id_인용은_prefix_유무와_무관하게_통과() {
            var bare = validator.validate(reportWith(Track.TRACK_1, List.of(REAL_POLICY_ID)));
            var prefixed = validator.validate(reportWith(Track.TRACK_1, List.of("inline:" + REAL_POLICY_ID)));

            assertThat(bare.passed()).isTrue();
            assertThat(prefixed.passed())
                    .as("inline: prefix 는 벗겨낸 뒤 같은 인덱스를 봐야 한다")
                    .isTrue();
        }

        @Test
        void 지어낸_정책id_인용은_차단된다() {
            var result = validator.validate(
                    reportWith(Track.TRACK_1, List.of("MORT_DSR_LIMIT_V9_존재하지않음")));

            assertThat(result.passed()).isFalse();
            assertThat(result.issues()).anyMatch(s -> s.contains("미존재"));
        }

        @Test
        void RAG_비활성_환경의_rag_인용은_통과하지_못한다() {
            // 인덱스가 없으면 "확인할 수 없음" 이지만 결과는 차단이어야 한다.
            // 여기가 열려 있으면 RAG 를 끈 환경에서 rag: 인용이 검증 없이 통과한다.
            var result = validator.validate(reportWith(Track.TRACK_1, List.of("rag:chunk-001")));

            assertThat(result.passed()).isFalse();
            assertThat(result.issues()).anyMatch(s -> s.contains("rag:chunk-001"));
        }

        @Test
        void Track2_는_실존_인용_2건을_요구한다() {
            var one = validator.validate(reportWith(Track.TRACK_2, List.of(REAL_POLICY_ID)));
            var two = validator.validate(
                    reportWith(Track.TRACK_2, List.of(REAL_POLICY_ID, OTHER_REAL_POLICY_ID)));

            assertThat(one.passed()).isFalse();
            assertThat(one.issues()).anyMatch(s -> s.contains("Track 2 인용 부족"));
            assertThat(two.passed()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // RAG 켜짐 — rag: 가 실제 RAG 빈으로 라우팅되는가
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "ai.rag.enabled=true",
            "ai.llm.provider=stub",
            "spring.datasource.url=jdbc:h2:mem:groundragdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.locations=classpath:db/h2-migration",
            "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                    "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration"
    })
    class RagOn {

        @Autowired
        private GroundingValidator validator;

        /** RagPolicyIndex 가 최종적으로 묻는 곳. 검색 인프라 없이 라우팅만 확인한다. */
        @MockBean
        private RagSearchBackend ragSearchBackend;

        @Test
        void rag_인용은_RAG_백엔드에_실존을_묻는다() {
            when(ragSearchBackend.existsBySourceId("policy_regulation", "chunk-001")).thenReturn(true);

            var result = validator.validate(reportWith(Track.TRACK_1, List.of("rag:chunk-001")));

            assertThat(result.passed())
                    .as("rag: prefix 는 벗겨낸 뒤 RAG 인덱스에 물어야 한다")
                    .isTrue();
        }

        @Test
        void RAG_백엔드가_모르는_chunk_는_차단된다() {
            when(ragSearchBackend.existsBySourceId("policy_regulation", "ghost")).thenReturn(false);

            var result = validator.validate(reportWith(Track.TRACK_1, List.of("rag:ghost")));

            assertThat(result.passed()).isFalse();
            assertThat(result.issues()).anyMatch(s -> s.contains("rag:ghost"));
        }

        @Test
        void RAG_가_켜져도_인라인_인용은_인라인_인덱스로_간다() {
            var result = validator.validate(
                    reportWith(Track.TRACK_2, List.of(REAL_POLICY_ID, "inline:" + OTHER_REAL_POLICY_ID)));

            assertThat(result.passed()).isTrue();
        }
    }
}
