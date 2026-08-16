package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트의 색이 두 곳에 적혀 있고, 그 둘이 어긋나면 잡는다.
 *
 * <p><b>왜 두 곳인가.</b> Tailwind 토큰({@code bg-kb-primary})은 클래스 이름에서만
 * 쓸 수 있다. 인라인 {@code style={{}}} 에서는 값이 필요하므로 {@code lib/theme.ts}
 * 가 같은 색을 상수로 한 벌 더 들고 있다. 없앨 수 있는 중복이 아니다.
 *
 * <p><b>그래서 규칙이 필요하다.</b> {@code theme.ts} 상단에 "값은 tailwind.config.ts
 * 의 동명 토큰과 항상 일치시켜야 한다" 고 적혀 있었지만, 그 문장을 확인하는 것이
 * 없었다. 실제로 목록이 어긋나 있었다 — 토큰은 서른 개인데 상수는 여덟 개뿐이라,
 * 상수가 없는 색은 인라인에 값을 그대로 적을 수밖에 없었다. 그렇게 적힌 것이
 * <b>361회</b>였고 그중 <b>183회</b>는 상수가 있는데도 값을 적은 것이었다.
 *
 * <p>지금은 1:1 이다. 이름은 토큰을 대문자·밑줄로 바꾼 것이고 값은 같다.
 *
 * <p><b>이 검사가 막지 못하는 것.</b> 토큰에도 상수에도 없는 색을 새로 적는 것은
 * 여기서 안 걸린다(그런 색이 아직 112회 있다). 그건 "무엇을 토큰으로 승격할까" 라는
 * 디자인 판단이라 기계가 정할 일이 아니다. 여기서 지키는 것은 <b>두 벌이 서로
 * 어긋나지 않는 것</b> 하나다.
 */
class ThemeTokenSyncTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();

    private static final Pattern TOKEN =
            Pattern.compile("\"(kb-[a-z0-9-]+)\"\\s*:\\s*\"(#[0-9A-Fa-f]{6})\"");
    private static final Pattern CONST =
            Pattern.compile("export const (KB_[A-Z0-9_]+)\\s*=\\s*'(#[0-9A-Fa-f]{6})'");

    @Test
    @DisplayName("theme.ts 상수와 tailwind 토큰이 1:1 이다")
    void constantsMirrorTokens() throws IOException {
        Map<String, String> tokens = read(WEB.resolve("tailwind.config.ts"), TOKEN);
        Map<String, String> constants = read(WEB.resolve("lib/theme.ts"), CONST);

        Map<String, String> expected = new LinkedHashMap<>();
        tokens.forEach((token, hex) -> expected.put(toConstantName(token), hex));

        assertThat(constants)
                .as("lib/theme.ts 는 tailwind.config.ts 의 색 토큰과 1:1 이어야 한다. "
                    + "토큰을 더하면 상수도 더한다 — 상수가 없으면 인라인 style 에 "
                    + "값을 그대로 적게 되고, 그것이 361회 쌓였던 원인이다.")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("같은 이름끼리 값이 같다")
    void valuesMatch() throws IOException {
        Map<String, String> tokens = read(WEB.resolve("tailwind.config.ts"), TOKEN);
        Map<String, String> constants = read(WEB.resolve("lib/theme.ts"), CONST);

        tokens.forEach((token, hex) -> {
            String name = toConstantName(token);
            if (constants.containsKey(name)) {
                assertThat(constants.get(name))
                        .as("%s 와 %s 의 값이 다르다 — 한쪽만 고치면 클래스와 인라인이 "
                            + "다른 색으로 보인다", token, name)
                        .isEqualTo(hex);
            }
        });
    }

    /** {@code kb-primary-bg} → {@code KB_PRIMARY_BG} */
    private static String toConstantName(String token) {
        return token.toUpperCase().replace('-', '_');
    }

    private static Map<String, String> read(Path file, Pattern pattern) throws IOException {
        assertThat(Files.exists(file)).as("%s 가 있어야 한다", file).isTrue();
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = pattern.matcher(Files.readString(file));
        while (m.find()) {
            found.putIfAbsent(m.group(1), m.group(2).toUpperCase());
        }
        assertThat(found).as("%s 에서 색을 하나도 못 읽었다 — 형식이 바뀌었나", file).isNotEmpty();
        return found;
    }
}
