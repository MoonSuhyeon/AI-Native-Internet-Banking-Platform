package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 문서가 상태를 밝히는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code docs/plan/} 에 계획서가 33건 쌓여 있었는데 어느 것이
 * 구현됐고 어느 것이 설계만인지 구분할 방법이 없었다. 완료 표시가 제각각이었다 —
 * 어떤 문서는 {@code - [x]} 체크, 어떤 문서는 ✅, 어떤 문서는 머리말의 "상태:" 줄,
 * 대부분은 아무것도.
 *
 * <p>그 상태가 <b>코드와 어긋난 것도 있었다.</b> {@code doc-agent.md} 는 머리말이
 * "구현 미착수" 라고 말하는데 {@code DocAgentApplication} 이 돌고 있었고,
 * {@code fds-realtime-detection.md} 는 "설계 — 승인 전" 인데 {@code services/fds-detector}
 * 모듈이 이미 있었다. 계획서를 쓰고 구현한 뒤 머리말을 고치지 않은 것이다.
 *
 * <p>이 상태가 나쁜 이유는 <b>양쪽으로 헛수고를 만들기 때문</b>이다. 설계만 있는
 * 문서를 구현된 것으로 읽으면 없는 API 를 부르고, 구현된 것을 설계만으로 읽으면
 * 이미 있는 것을 다시 만든다.
 *
 * <p><b>무엇을 보는가.</b> 형식만 본다 — 상태 줄이 있는지, 어휘가 넷 중 하나인지,
 * 색인에 빠진 문서가 없는지. <b>상태가 사실인지는 보지 않는다.</b> 그건 코드를 짠
 * 사람이 고쳐야 하는 것이고, 기계가 판정하려 들면 거짓 경보가 난다.
 */
class PlanStatusTest {

    private static final Path PLAN_DIR = Path.of("..", "docs", "plan").toAbsolutePath().normalize();
    private static final Path INDEX = PLAN_DIR.resolve("README.md");

    /** 늘리면 다시 제각각이 된다. 넷으로 못 박는다. */
    private static final Set<String> ALLOWED = Set.of(
            "✅ 구현됨", "🔨 진행 중", "📐 설계만 — 코드 없음", "🗑 폐기 — 따르지 말 것");

    private static final Pattern STATUS_LINE = Pattern.compile("> \\*\\*상태: (.+?)\\*\\* — (.+)");

    private static List<Path> planDocs() throws IOException {
        try (Stream<Path> files = Files.list(PLAN_DIR)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("모든 계획 문서가 상태를 밝힌다")
    void everyPlanDeclaresStatus() throws IOException {
        Set<String> silent = new TreeSet<>();
        for (Path doc : planDocs()) {
            if (!STATUS_LINE.matcher(Files.readString(doc)).find()) {
                silent.add(doc.getFileName().toString());
            }
        }

        assertThat(silent)
                .as("상태 줄이 없는 계획 문서다. 읽는 사람이 구현됐는지 설계만인지 "
                    + "알 수 없어, 없는 API 를 부르거나 있는 것을 다시 만든다. "
                    + "형식은 docs/plan/README.md 참조")
                .isEmpty();
    }

    @Test
    @DisplayName("상태 어휘는 넷뿐이다")
    void statusVocabularyIsClosed() throws IOException {
        Set<String> unknown = new TreeSet<>();
        for (Path doc : planDocs()) {
            Matcher m = STATUS_LINE.matcher(Files.readString(doc));
            if (m.find() && !ALLOWED.contains(m.group(1))) {
                unknown.add(doc.getFileName() + " → " + m.group(1));
            }
        }

        assertThat(unknown)
                .as("정해진 넷(구현됨·진행 중·설계만·폐기) 밖의 상태다. "
                    + "어휘를 늘리면 결국 문서마다 제각각이 되고, 그게 원래 문제였다")
                .isEmpty();
    }

    @Test
    @DisplayName("색인이 모든 계획 문서를 담는다")
    void indexCoversEveryPlan() throws IOException {
        String index = Files.readString(INDEX);

        Set<String> missing = new TreeSet<>();
        for (Path doc : planDocs()) {
            String name = doc.getFileName().toString();
            if (!index.contains("(" + name + ")")) {
                missing.add(name);
            }
        }

        assertThat(missing)
                .as("색인에 없는 계획 문서다. 목록에서 안 보이면 없는 것과 같다")
                .isEmpty();
    }
}
