package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p><b>사각지대가 있었다.</b> 이 검사는 오래도록 {@code web/lib/*.ts} 만 봤다.
 * 그런데 프론트에는 두 번째 경로가 있다 — {@code web/app/api/**\/route.ts} 의
 * 서버측 프록시다. 브라우저가 상대경로를 부르면 Next 서버가 대신 서비스를 호출하므로
 * lib 을 아무리 정리해도 이쪽으로 우회가 남는다.
 *
 * <p>실제로 그랬다. 상담 프록시는 게이트웨이가 아니라 서비스를 직접 부르면서
 * {@code Content-Type} 외의 헤더를 전부 버렸다. 상담 서비스의 직원 엔드포인트는
 * 게이트웨이가 주입한 {@code X-Employee-Id} 를 요구하므로, 관리자 채팅 화면은
 * 실제로 인가를 통과하지 못하는 상태였다.
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

    /** 서버측 프록시가 사는 곳. 브라우저가 아니라 Next 서버가 여기서 서비스를 부른다. */
    private static final Path WEB_API =
            Path.of("..", "web", "app", "api").toAbsolutePath().normalize();

    /**
     * 자격증명을 실어 보낼 수 없는 프록시들 — 그 시점에 토큰이 아직 없다.
     *
     * <p>목록으로 두는 이유는 예외를 숨기지 않기 위해서다. 새 프록시는 인증을
     * 전달하거나, 왜 전달하지 않아도 되는지를 여기 적어야 한다. 둘 중 하나를
     * 고르게 만드는 것이 이 목록의 목적이다.
     */
    private static final Map<String, String> NO_CREDENTIAL_YET = Map.of(
            "auth/login", "로그인 — 자격증명은 본문으로 간다",
            "auth/cert-login", "인증서 로그인 — 토큰 발급 전",
            "auth/qr/generate", "QR 발급 — 로그인 전 단계",
            "auth/qr/status", "QR 상태 폴링 — 로그인 전 단계",
            "auth/qr-cert/generate", "인증서 QR 발급 — 로그인 전 단계",
            "auth/qr-cert/status", "인증서 QR 상태 — 로그인 전 단계",
            "auth/qr-cert/approve", "인증서 QR 승인 — 발급 토큰으로 대신한다",
            "v1/auth/login", "로그인 — 자격증명은 본문으로 간다",
            "v1/auth/cert-login", "인증서 로그인 — 토큰 발급 전",
            "customer/cert-login", "인증서 로그인 — 토큰 발급 전");

    /** 사용자 자격증명이 아니라 서버 자신의 자격으로 부르는 프록시. */
    private static final Map<String, String> SERVER_OWN_CREDENTIAL = Map.of(
            "monitoring/dashboards", "Grafana 임베드 — 대시보드 목록이지 고객 데이터가 아니다",
            "other-bank", "타행 수신은행 시연(lab 존) — 실제 은행이면 금결원 망으로 간다");

    /**
     * 게이트웨이 경로가 아직 없어 직통으로 남은 것.
     *
     * <p>예외를 지우지 않고 적어 두는 이유는, 이유 없이 남아 있는 것과 이유가 있어
     * 남아 있는 것을 구별하기 위해서다. 게이트웨이에 경로가 생기면 여기서 지운다.
     */
    private static final Map<String, String> NO_GATEWAY_ROUTE_YET = Map.of(
            "agent/spending",
            "goal-agent 는 compose 에 없고 호스트(:8000)에서 돈다. 게이트웨이 라우트도 "
                    + "아직 없다. 지금 기본값 8086 은 아무도 듣지 않는 죽은 포트다 — "
                    + "OPEN_ITEMS 참조");

    @Test
    @DisplayName("서버측 프록시가 호출자의 자격증명을 버리지 않는다")
    void proxiesForwardCredentials() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : routeFiles()) {
            String route = routeName(file);
            if (NO_CREDENTIAL_YET.containsKey(route) || SERVER_OWN_CREDENTIAL.containsKey(route)) {
                continue;
            }
            String src = Files.readString(file);
            if (!src.toLowerCase(Locale.ROOT).contains("authorization")) {
                offenders.add(route);
            }
        }

        assertThat(offenders)
                .as("프록시가 Authorization 을 버리면 서비스에 신원 없이 도착한다. "
                    + "전달하거나, 왜 필요 없는지를 NO_CREDENTIAL_YET·SERVER_OWN_CREDENTIAL "
                    + "에 적을 것.")
                .isEmpty();
    }

    @Test
    @DisplayName("서버측 프록시가 서비스 포트를 직접 가리키지 않는다")
    void proxiesTargetTheGateway() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : routeFiles()) {
            String route = routeName(file);
            if (SERVER_OWN_CREDENTIAL.containsKey(route)
                    || NO_GATEWAY_ROUTE_YET.containsKey(route)) {
                continue;  // 게이트웨이 대상이 아니거나, 아직 경로가 없는 것들
            }
            for (String line : Files.readString(file).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                Matcher port = HARDCODED_SERVICE_PORT.matcher(line);
                if (port.find()) {
                    offenders.add(route + " → " + port.group());
                }
            }
        }

        assertThat(offenders)
                .as("프록시가 서비스를 직접 부르면 게이트웨이의 신원 검증·헤더 주입을 "
                    + "건너뛴다. 게이트웨이를 가리킬 것.")
                .isEmpty();
    }

    /** {@code app/api/consultation/[...path]/route.ts} → {@code consultation} */
    private static String routeName(Path file) {
        String rel = WEB_API.relativize(file.getParent()).toString().replace('\\', '/');
        return rel.replaceAll("/\\[\\.\\.\\.[^\\]]+\\]$", "");
    }

    private static List<Path> routeFiles() throws IOException {
        assertThat(Files.exists(WEB_API)).as("%s 가 있어야 한다", WEB_API).isTrue();
        try (Stream<Path> paths = Files.walk(WEB_API)) {
            return paths.filter(p -> p.getFileName().toString().equals("route.ts")).toList();
        }
    }

    private static List<Path> tsFiles() throws IOException {
        assertThat(Files.exists(WEB_LIB)).as("%s 가 있어야 한다", WEB_LIB).isTrue();
        try (Stream<Path> paths = Files.list(WEB_LIB)) {
            return paths.filter(p -> p.toString().endsWith(".ts")).toList();
        }
    }
}
