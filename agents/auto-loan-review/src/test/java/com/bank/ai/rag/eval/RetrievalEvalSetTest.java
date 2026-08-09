package com.bank.ai.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가셋이 실제 코퍼스와 맞물려 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 평가셋은 한 번 쓰고 잊히기 쉽다. 정책이 바뀌어 문서 id 가
 * 사라지거나 이름이 바뀌면, 평가는 <b>존재하지 않는 정답</b>을 요구하게 된다.
 * 그러면 멀쩡한 검색기가 계속 실패로 나오고, 사람들은 결국 그 숫자를 안 보게 된다.
 *
 * <p>반대 방향도 있다. 정답을 빠뜨린 질의는 늘 0점이 되어 전체 점수를 끌어내린다.
 *
 * <p>이 테스트는 검색을 돌리지 않는다. <b>평가셋 자체의 건강</b>만 본다 —
 * 실제 검색 품질은 백엔드가 있는 환경에서 잰다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalSetTest {

    private static final String EVAL_SET = "/rag/retrieval-eval-set.json";

    /** application.yml 의 policy.inline 아래 문서 id 들. 코퍼스의 정본이다. */
    private static final Path APPLICATION_YML =
            Path.of("src/main/resources/application.yml");

    private JsonNode evalSet;
    private Set<String> corpusIds;

    @BeforeAll
    void load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(EVAL_SET)) {
            assertThat(in).as("평가셋 파일이 없다: %s", EVAL_SET).isNotNull();
            evalSet = new ObjectMapper().readTree(in);
        }
        corpusIds = readCorpusIds();
    }

    @Test
    @DisplayName("평가셋이 비어 있지 않다 — 비면 통과하지만 아무것도 재지 않는다")
    void evalSetIsNotEmpty() {
        assertThat(evalSet.path("queries"))
                .as("질의가 없으면 평가는 언제나 성공하고 품질 저하를 못 잡는다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("정답 문서가 실제 코퍼스에 존재한다")
    void relevantDocumentsExistInCorpus() {
        List<String> missing = new ArrayList<>();
        for (JsonNode q : evalSet.path("queries")) {
            for (JsonNode rel : q.path("relevant")) {
                if (!corpusIds.contains(rel.asText())) {
                    missing.add(q.path("id").asText() + " → " + rel.asText());
                }
            }
        }

        assertThat(missing)
                .as("평가셋이 없는 문서를 정답으로 요구한다. 정책이 바뀌었는데 "
                    + "평가셋을 안 고친 것이다 — 멀쩡한 검색기가 계속 실패로 나온다. "
                    + "코퍼스 문서: %s", corpusIds)
                .isEmpty();
    }

    @Test
    @DisplayName("모든 질의에 정답이 하나 이상 있다")
    void everyQueryHasRelevantDocuments() {
        List<String> empty = new ArrayList<>();
        for (JsonNode q : evalSet.path("queries")) {
            if (q.path("relevant").isEmpty()) {
                empty.add(q.path("id").asText());
            }
        }

        assertThat(empty)
                .as("정답 없는 질의는 늘 0점이 되어 전체 점수를 끌어내린다. "
                    + "지우거나 정답을 채워야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("질의 id 가 겹치지 않는다")
    void queryIdsAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> dup = new ArrayList<>();
        for (JsonNode q : evalSet.path("queries")) {
            if (!seen.add(q.path("id").asText())) {
                dup.add(q.path("id").asText());
            }
        }

        assertThat(dup)
                .as("id 가 겹치면 결과를 짝지을 때 하나가 조용히 덮인다")
                .isEmpty();
    }

    @Test
    @DisplayName("코퍼스 문서 대부분이 평가셋에 등장한다")
    void corpusIsReasonablyCovered() {
        Set<String> covered = new LinkedHashSet<>();
        for (JsonNode q : evalSet.path("queries")) {
            for (JsonNode rel : q.path("relevant")) {
                covered.add(rel.asText());
            }
        }

        Set<String> uncovered = new LinkedHashSet<>(corpusIds);
        uncovered.removeAll(covered);

        // 전부를 요구하지는 않는다 — 새 정책이 들어온 직후에는 잠시 비는 것이 정상이다.
        // 다만 절반을 넘게 비면 평가가 코퍼스의 일부만 보고 있다는 뜻이다.
        assertThat(uncovered.size())
                .as("평가셋이 다루지 않는 문서가 너무 많다. 그 문서들의 검색 품질은 "
                    + "재지 않고 있다: %s", uncovered)
                .isLessThanOrEqualTo(corpusIds.size() / 2);
    }

    /**
     * application.yml 의 {@code policy.inline} 키를 읽는다.
     *
     * <p>Spring 을 띄우지 않는다. 이 테스트가 보는 것은 "설정에 적힌 문서 목록" 이고,
     * 컨텍스트를 띄우면 느려질 뿐 검증 대상이 달라지지 않는다.
     */
    private Set<String> readCorpusIds() throws IOException {
        String yml = Files.readString(APPLICATION_YML);
        int start = yml.indexOf("    inline:");
        assertThat(start).as("application.yml 에서 policy.inline 을 찾지 못했다").isGreaterThan(0);

        // inline 바로 아래 6칸 들여쓰기 키가 문서 id 다.
        String block = yml.substring(start);
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^ {6}([A-Z][A-Z0-9_]*):\\s*$").matcher(block);
        while (m.find()) {
            ids.add(m.group(1));
        }
        assertThat(ids)
                .as("문서 id 를 하나도 못 읽었다면 파싱이 깨진 것이다 — "
                    + "그대로 두면 이 테스트가 아무것도 검증하지 않는다")
                .isNotEmpty();
        return ids;
    }
}
