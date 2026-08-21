package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장된 토큰을 검증 없이 읽는 곳이 없는지 확인한다.
 *
 * <p><b>왜 필요한가.</b> 프론트 라우트 몇 개가 백엔드를 거치지 않고
 * {@code mock.<base64>.<시각>} 형태의 가짜 토큰을 내주던 시절이 있었다. 그
 * 라우트는 지웠지만 <b>이미 브라우저에 저장된 토큰은 지워지지 않는다.</b>
 *
 * <p>남아 있으면 최악의 상태가 된다.
 *
 * <ul>
 *   <li>헤더는 토큰이 있으니 <b>로그인된 것으로 표시</b>한다</li>
 *   <li>게이트웨이는 그 토큰을 거절하므로 <b>모든 API 가 401</b></li>
 *   <li>화면과 실제가 어긋난 채로 사용자는 "왜 안 되지" 만 겪는다</li>
 * </ul>
 *
 * <p>실제로 이 상태가 났다. 챗봇 인증서 로그인을 시험하면서 가짜 토큰이 저장됐고,
 * mock 라우트를 지운 뒤에도 그 브라우저에서는 "My AXful 이 안 뜬다" 로 나타났다.
 *
 * <p><b>읽는 곳이 여덟 군데로 흩어져 있었다.</b> 한 곳만 고쳐서는 소용이 없다 —
 * 검증을 안 거치는 화면 하나만 남아도 그 화면에서 같은 상태가 재현된다.
 * 그래서 {@code lib/token.ts} 의 {@code readAccessToken} 하나로 모으고, 직접 읽는
 * 코드가 다시 생기지 않게 여기서 못 박는다.
 *
 * <p><b>보지 않는 것.</b> 서명 검증은 게이트웨이의 일이다. 여기서 막는 것은
 * "검증 지점을 우회해 저장소를 직접 읽는" 한 종류다.
 */
class TokenReadPathTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();

    /** 검증을 거치지 않고 토큰을 꺼내는 표현. */
    private static final List<String> DIRECT_READS = List.of(
            "localStorage.getItem('accessToken')",
            "localStorage.getItem('access_token')",
            "localStorage.getItem(\"accessToken\")",
            "localStorage.getItem(\"access_token\")");

    /** 검증 함수가 사는 곳. 여기서는 당연히 직접 읽는다. */
    private static final String VALIDATOR = "lib/token.ts";

    @Test
    @DisplayName("토큰을 검증 없이 직접 읽는 화면이 없다")
    void everyTokenReadGoesThroughTheValidator() throws IOException {
        Set<String> offenders = new TreeSet<>();

        for (String dir : List.of("app", "components", "lib")) {
            Path root = WEB.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".ts") && !name.endsWith(".tsx")) {
                        continue;
                    }
                    String rel = WEB.relativize(f).toString().replace('\\', '/');
                    if (rel.equals(VALIDATOR)) {
                        continue;
                    }
                    String text = Files.readString(f);
                    for (String marker : DIRECT_READS) {
                        if (text.contains(marker)) {
                            offenders.add(rel);
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("토큰을 lib/token.ts 의 readAccessToken 을 거치지 않고 직접 읽는다. "
                    + "유효하지 않은 토큰이 남아 있으면 그 화면만 '로그인은 됐는데 "
                    + "API 는 전부 401' 인 상태가 되고, 화면과 실제가 어긋난 채로 "
                    + "원인을 찾기 어려워진다")
                .isEmpty();
    }
}
