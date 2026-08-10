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
 * 프론트가 서비스를 직접 부르지 않는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 이 레포의 인가는 대부분 "게이트웨이가 주입한 신원 헤더"
 * 위에 서 있다 — {@code X-Customer-Id}, {@code X-Employee-Id}, {@code X-User-Role}.
 * 게이트웨이는 클라이언트가 붙인 같은 이름의 헤더를 지우고 검증된 JWT 클레임으로
 * 덮어쓴다.
 *
 * <p>그래서 프론트가 서비스 주소를 직접 가리키면 그 헤더가 아예 붙지 않고,
 * <b>인가가 통째로 무의미해진다</b>. 서비스 쪽 코드가 아무리 정확해도 그렇다.
 *
 * <p>실제로 그 상태였다. 화면별 클라이언트 8개가 각자 {@code NEXT_PUBLIC_*_API_URL}
 * 로 서비스를 직접 가리켰다. 그중 둘({@code auto-review-api}, {@code ai-api})은
 * 그 경로에 인가를 붙이자마자 화면이 깨졌을 것이다 — 신원 헤더가 없으니 전부 거절된다.
 *
 * <p>되돌리기는 쉽다. 환경변수 한 줄이면 우회로가 되살아나고, 되살아나도 화면은
 * 멀쩡히 돈다. 그래서 여기서 못 박는다.
 */
class FrontendGatewayOnlyTest {

    private static final Path WEB_LIB =
            Path.of("..", "web", "lib").toAbsolutePath().normalize();

    /**
     * 서비스별 직통 주소로 쓰이던 환경변수들.
     *
     * <p>게이트웨이 주소({@code NEXT_PUBLIC_API_URL})와 모니터링 임베드
     * ({@code NEXT_PUBLIC_GRAFANA_URL})는 제외한다 — 앞은 일원화의 목적지이고,
     * 뒤는 API 가 아니라 대시보드 iframe 이다.
     */
    private static final Pattern DIRECT_SERVICE_URL = Pattern.compile(
            "NEXT_PUBLIC_(?!API_URL|GRAFANA_URL|DEMO_MODE)[A-Z_]*API_URL");

    /** 서비스 포트를 직접 적은 흔적. 환경변수를 없애도 상수로 남을 수 있다. */
    private static final Pattern HARDCODED_SERVICE_PORT =
            Pattern.compile("localhost:(8081|8082|8083|8084|8086|8087|8089|8090|8091)");

    @Test
    @DisplayName("프론트 클라이언트가 서비스 직통 주소를 쓰지 않는다")
    void noDirectServiceBaseUrls() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : tsFiles()) {
            String src = Files.readString(file);
            String name = file.getFileName().toString();

            for (String line : src.split("\n")) {
                // 주석은 건너뛴다 — 왜 없앴는지 설명하며 이름을 언급하기 때문이다.
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                Matcher direct = DIRECT_SERVICE_URL.matcher(line);
                if (direct.find()) {
                    offenders.add(name + " → " + direct.group());
                }
                Matcher port = HARDCODED_SERVICE_PORT.matcher(line);
                if (port.find()) {
                    offenders.add(name + " → " + port.group());
                }
            }
        }

        assertThat(offenders)
                .as("프론트가 서비스를 직접 부르면 게이트웨이가 주입하는 신원 헤더가 "
                    + "붙지 않는다. 서비스 쪽 인가가 아무리 정확해도 무의미해진다. "
                    + "게이트웨이(NEXT_PUBLIC_API_URL)를 거치게 할 것.")
                .isEmpty();
    }

    private static List<Path> tsFiles() throws IOException {
        assertThat(Files.exists(WEB_LIB)).as("%s 가 있어야 한다", WEB_LIB).isTrue();
        try (Stream<Path> paths = Files.list(WEB_LIB)) {
            return paths.filter(p -> p.toString().endsWith(".ts")).toList();
        }
    }
}
