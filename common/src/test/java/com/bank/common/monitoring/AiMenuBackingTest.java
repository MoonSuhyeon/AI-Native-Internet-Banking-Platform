package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "AI" 라고 이름 붙인 메뉴에 실제 에이전트가 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 홈 퀵메뉴 다섯 개가 전부 "AI" 로 시작했지만 실제로는
 * 평범한 정적 페이지로 갔다. "AI 자산분석" 은 그냥 계좌조회였고, "AI 상품 추천" 은
 * 그냥 예금 목록이었다. 그중 <b>"AI 음성 어시스턴트" 는 대응하는 코드가 레포에 아예
 * 없었고</b> 링크는 엉뚱하게 대출현황을 가리켰다.
 *
 * <p>이 실패는 눌러도 페이지가 뜨기 때문에 <b>죽은 버튼보다 찾기 어렵다.</b>
 * 404 도 아니고 오류도 아니다 — 그냥 라벨이 거짓말을 한다.
 *
 * <p><b>무엇을 보는가.</b> 라벨이 "AI" 로 시작하는 퀵메뉴 항목마다 뒷받침하는 것이
 * 있는지 본다. 둘 중 하나면 통과다.
 *
 * <ul>
 *   <li>{@code ask} — 챗봇 에이전트를 부른다(consultation 의 세 에이전트)</li>
 *   <li>{@code href} 가 아래 표에 있다 — 그 경로에 실제 AI 백엔드가 물려 있다</li>
 * </ul>
 *
 * <p><b>보지 않는 것.</b> 그 에이전트가 좋은 답을 내는지는 보지 않는다. 여기서
 * 막는 것은 "AI 라고 써 놓고 뒤에 아무것도 없는" 한 종류다.
 */
class AiMenuBackingTest {

    private static final Path MENU = Path.of(
            "..", "web", "components", "home", "HeroWithQuickMenu.tsx").toAbsolutePath().normalize();

    /** AI 백엔드가 물려 있는 경로. 값은 근거가 되는 모듈이다. */
    private static final Map<String, String> AI_BACKED_ROUTES = new LinkedHashMap<>();

    static {
        // 대출 신청이 auto-loan-review 의 사전심사를 트리거한다.
        AI_BACKED_ROUTES.put("/loans/apply", "agents/auto-loan-review");
        // 지출 분석 화면이 /api/agent/spending 을 부른다.
        AI_BACKED_ROUTES.put("/spending", "agents/consultation spending_pattern_agent");
        // 조사 콘솔이 /api/v1/internal/fraud/** 로 조사 에이전트를 부른다.
        // 직원 전용이라 로그인을 거치지만, 그 화면이 부르는 것은 실제 에이전트다.
        AI_BACKED_ROUTES.put("/admin/fraud", "agents/fraud-investigation-agent");
    }

    private static final Pattern ENTRY = Pattern.compile(
            "label:\\s*'([^']*)'\\s*,\\s*(ask|href):\\s*'([^']*)'");

    @Test
    @DisplayName("AI 라고 이름 붙인 퀵메뉴는 실제 에이전트를 가리킨다")
    void everyAiMenuHasABackend() throws IOException {
        String source = Files.readString(MENU);

        Set<String> unbacked = new TreeSet<>();
        int entries = 0;

        Matcher m = ENTRY.matcher(source);
        while (m.find()) {
            String label = m.group(1);
            String kind = m.group(2);
            String value = m.group(3);
            entries++;
            if (!label.startsWith("AI")) {
                continue;
            }

            // 챗봇에게 물어보는 것은 그 자체가 에이전트 호출이다.
            if (kind.equals("ask")) {
                continue;
            }
            if (!AI_BACKED_ROUTES.containsKey(value)) {
                unbacked.add(label + " → " + value);
            }
        }

        // 세는 것은 <b>읽어 낸 메뉴 전체</b>다. 예전에는 'AI' 로 시작하는 항목만 셌는데,
        // 라벨에서 'AI' 를 빼자(메뉴를 금융 기능 이름으로 바꾸면서) 0 이 되어 검사가
        // 실패했다 — 정작 막으려던 문제는 없는데도. 파싱이 살아 있는지는 전체 개수로
        // 확인하고, 'AI' 라벨이 다시 생기면 그때 아래 검사가 그것을 잡는다.
        assertThat(entries)
                .as("퀵메뉴를 하나도 못 읽었다 — 파싱이 깨졌거나 메뉴가 사라졌다. "
                    + "어느 쪽이든 이 검사는 아무것도 지키지 않는 상태다")
                .isPositive();

        assertThat(unbacked)
                .as("'AI' 라고 이름 붙었는데 뒤에 에이전트가 없다. 눌러도 페이지는 "
                    + "뜨기 때문에 죽은 버튼보다 찾기 어렵다 — 404 도 오류도 아니고 "
                    + "라벨이 거짓말을 할 뿐이다. 챗봇 질문(ask)으로 잇거나, "
                    + "AI 백엔드가 물린 경로를 쓰거나, 라벨에서 'AI' 를 뺄 것")
                .isEmpty();
    }
}
