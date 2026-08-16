package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다온은행 화면이 AXful 브랜드 색을 쓰지 않는지 본다.
 *
 * <p><b>왜 필요한가.</b> 다온은행({@code payment-service-b})은 타행 수신은행 시연이다.
 * 다른 은행이므로 디자인이 <b>달라야</b> 한다 — 같아 보이면 "타행으로 보냈다" 는 시연
 * 자체가 성립하지 않는다.
 *
 * <p><b>실제로 어겼다.</b> 색을 토큰으로 정리하면서 다온의 브랜드 네이비 {@code #1B3A6B}
 * 를 AXful 토큰({@code kb-admin} · {@code KB_GNB_BIZ_ACTIVE})으로 바꿔 놓았다.
 * 값이 같아서 화면은 그대로였고, 그래서 아무 검사도 걸리지 않았다. 문제는 나중이다 —
 * AXful 색을 바꾸는 순간 다온이 따라 바뀐다. <b>지금 안 보이는 결합</b>이라 더 위험하다.
 *
 * <p>지금은 {@code daon-navy} · {@code DAON_NAVY} 로 분리돼 있다. 값은 같지만 이름이
 * 다르므로 한쪽만 바꿀 수 있다.
 *
 * <p><b>중립 토큰은 막지 않는다.</b> {@code kb-text} · {@code kb-border} 같은 글자·
 * 테두리 회색은 브랜드 정체성이 아니고, 다온 화면이 처음부터 그것을 쓰고 있었다.
 * 여기서 막는 것은 <b>브랜드를 나르는 색</b>뿐이다.
 */
class DaonPaletteIsolationTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();

    /**
     * AXful 의 정체성을 나르는 토큰·상수.
     *
     * <p>초록(브랜드)·네이비(어드민/기업 GNB)·챗봇 그린·골드가 여기 든다. 회색과
     * 상태색(red/blue)은 뺐다 — 어느 은행 화면에서나 같은 뜻이라 정체성이 아니다.
     */
    private static final Pattern AXFUL_BRAND = Pattern.compile(
            "(?:bg|text|border|from|to|ring|hover:bg|hover:text|hover:border)-kb-(?:primary|admin|gnb-biz-active|chat|gold|mint|yellow|beige|taupe)[a-z-]*"
                    + "|\\bKB_(?:PRIMARY|ADMIN|GNB_BIZ_ACTIVE|CHAT|GOLD|MINT|YELLOW|BEIGE|TAUPE)[A-Z_]*\\b");

    @Test
    @DisplayName("다온은행 화면이 AXful 브랜드 색을 쓰지 않는다")
    void daonDoesNotUseAxfulBrandColors() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : daonFiles()) {
            String src = Files.readString(file);
            String name = WEB.relativize(file).toString().replace('\\', '/');
            int lineNo = 0;
            for (String line : src.split("\n")) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;  // 주석은 왜 안 쓰는지 설명하며 이름을 언급한다
                }
                Matcher m = AXFUL_BRAND.matcher(line);
                if (m.find()) {
                    offenders.add(name + ":" + lineNo + " → " + m.group());
                }
            }
        }

        assertThat(offenders)
                .as("다온은행은 타행이라 디자인이 달라야 한다. 값이 같더라도 AXful "
                    + "토큰을 빌려 쓰면 AXful 색을 바꿀 때 다온이 따라 바뀐다. "
                    + "daon-* / DAON_* 를 쓸 것.")
                .isEmpty();
    }

    @Test
    @DisplayName("다온 팔레트가 실제로 쓰이고 있다")
    void daonPaletteIsUsed() throws IOException {
        long used = 0;
        for (Path file : daonFiles()) {
            String src = Files.readString(file);
            if (src.contains("daon-navy") || src.contains("DAON_NAVY")) {
                used++;
            }
        }
        assertThat(used)
                .as("다온 화면 어디에서도 다온 팔레트를 안 쓰면, 위 검사가 통과하는 이유가 "
                    + "'브랜드 색을 아예 안 쓴다' 라는 뜻이 되어 검사가 무의미해진다")
                .isGreaterThan(0);
    }

    private static List<Path> daonFiles() throws IOException {
        List<Path> roots = List.of(WEB.resolve("app/other-bank"), WEB.resolve("components/other-bank"));
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            assertThat(Files.exists(root)).as("%s 가 있어야 한다", root).isTrue();
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".tsx")).forEach(files::add);
            }
        }
        return files;
    }
}
