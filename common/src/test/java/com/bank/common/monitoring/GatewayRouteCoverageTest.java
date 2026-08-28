package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트가 부르는 경로가 게이트웨이 라우트에 실제로 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@link FrontendGatewayOnlyTest} 는 프론트가 게이트웨이를
 * <b>거치는지</b>를 본다. 거치기만 하고 라우트가 없으면 요청은 게이트웨이에서 404 로
 * 끝난다 — 그런데 그 실패가 조용하다.
 *
 * <p>실제로 그 상태였다. 출금계좌 관리 화면이 {@code /api/v1/banking/withdrawal-accounts}
 * 를 부르는데 게이트웨이 라우트 목록에 {@code /api/v1/banking/**} 이 없었다.
 * 컨트롤러도 있고 화면도 있고 테스트도 통과했다. 목록이 늘 비어 보였을 뿐이다.
 * 화면이 "등록된 계좌가 없습니다" 를 보여 주는 것과 구분되지 않는다.
 *
 * <p>그래서 양쪽을 대조한다. 프론트 소스에서 {@code /api/...} 문자열을 모아 게이트웨이
 * 설정의 {@code Path=} 술어와 맞춰 본다.
 *
 * <p><b>보장하지 않는 것.</b> 라우트가 있다고 그 뒤 서비스에 그 핸들러가 있다는 뜻은
 * 아니다. 그건 각 서비스의 컨트롤러 테스트가 본다. 여기서 막는 것은 "게이트웨이까지
 * 도달하지도 못하는" 한 종류의 실패다.
 */
class GatewayRouteCoverageTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();
    private static final Path GATEWAY_YML = Path.of(
            "..", "services", "api-gateway", "src", "main", "resources", "application.yml")
            .toAbsolutePath().normalize();

    /** 프론트 소스에 적힌 API 경로 문자열. 템플릿 리터럴의 앞부분까지 잡는다. */
    private static final Pattern API_PATH = Pattern.compile("['\"`](/api/[A-Za-z0-9/_.-]*)");

    /**
     * 게이트웨이가 아니라 Next 자신이 처리하는 경로.
     *
     * <p>{@code web/app/api/**} 의 라우트 핸들러는 브라우저에게는 상대경로이고,
     * 게이트웨이로 가지 않는다. 그쪽이 게이트웨이를 거치는지는
     * {@link FrontendGatewayOnlyTest} 가 따로 본다.
     *
     * <p>목록을 손으로 적지 않고 파일 구조에서 읽는다 — 적어 두면 프록시를 하나
     * 추가할 때마다 여기도 고쳐야 하고, 잊으면 이 검사가 거짓 경보를 낸다.
     */
    private static Set<String> nextLocalPrefixes() throws IOException {
        Path apiDir = WEB.resolve("app").resolve("api");
        if (!Files.isDirectory(apiDir)) {
            return Set.of();
        }
        Set<String> prefixes = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(apiDir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (!f.getFileName().toString().equals("route.ts")) {
                    continue;
                }
                // .../app/api/auth/cert-login/route.ts → /api/auth/cert-login
                String rel = String.join("/", apiDir.relativize(f.getParent()).toString()
                        .split(Pattern.quote(java.io.File.separator)));
                // [...path] 같은 동적 구간은 그 앞까지가 접두사다.
                int dynamic = rel.indexOf('[');
                if (dynamic >= 0) {
                    rel = rel.substring(0, dynamic);
                }
                prefixes.add(("/api/" + rel).replaceAll("/+$", ""));
            }
        }
        return prefixes;
    }

    /**
     * 라우트가 없는 것이 <b>맞는</b> 경로.
     *
     * <p>목록으로 두는 이유는, 비워 두면 이 검사가 통과하려고 아무 라우트나 붙이게
     * 되기 때문이다. 이유를 적어 두면 다음 사람이 판단을 다시 밟을 수 있다.
     *
     * <p>{@code /api/ai/**} — auto-loan-review 는 loan-service 만 부르는 서비스 간
     * API 다(compose 가 호스트 포트를 열지 않는다). 게이트웨이에 얹으면 로그인한
     * 누구나 자동심사를 돌릴 수 있게 되는데, 그쪽은 내부 토큰만 보고 역할은 보지
     * 않는다. 어드민 시뮬레이터 화면(admin/loan/auto-review-sim)이 이 경로를 부르므로
     * 지금은 동작하지 않는다 — docs/OPEN_ITEMS.md 에 적어 두었다.
     */
    private static final Set<String> INTENTIONALLY_UNROUTED = Set.of();

    @Test
    @DisplayName("프론트가 부르는 /api 경로는 모두 게이트웨이 라우트에 있다")
    void everyFrontendPathHasARoute() throws IOException {
        List<String> routePatterns = gatewayPathPatterns();
        assertThat(routePatterns)
                .as("게이트웨이 설정에서 Path= 술어를 하나도 읽지 못했다 — 검사가 의미 없어진다")
                .isNotEmpty();

        Set<String> nextLocal = nextLocalPrefixes();
        Set<String> unrouted = new TreeSet<>();
        for (String path : frontendApiPaths()) {
            if (nextLocal.stream().anyMatch(path::startsWith)) {
                continue;
            }
            if (INTENTIONALLY_UNROUTED.stream()
                    .anyMatch(p -> path.equals(p) || path.startsWith(p + "/"))) {
                continue;
            }
            if (routePatterns.stream().noneMatch(p -> matches(p, path))) {
                unrouted.add(path);
            }
        }

        assertThat(unrouted)
                .as("게이트웨이 라우트가 없는 경로다. 화면은 멀쩡하고 응답만 404 라 "
                    + "'데이터가 없다' 와 구분되지 않는다. api-gateway 의 routes 에 추가해야 한다")
                .isEmpty();
    }

    /** {@code /api/v1/banking/**} 같은 술어를 실제 경로와 맞춰 본다. */
    private static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix + "/") && !path.substring(prefix.length() + 1).contains("/");
        }
        return path.equals(pattern);
    }

    /**
     * 주석을 걷어낸다.
     *
     * <p>없앤 경로를 <b>주석으로 설명하는 것</b>은 지워야 할 코드가 아니라 남겨야 할
     * 기록이다. 걷어내지 않으면 "예전에는 `/api/auth/cert-login` 을 불렀다" 는
     * 설명이 호출로 읽혀, 이미 지운 경로에 라우트를 만들라고 요구한다.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;

        for (String line : source.split("\n")) {
            String trimmed = line.trim();

            if (inBlock) {
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*") || trimmed.startsWith("{/*")) {
                if (!trimmed.contains("*/")) {
                    inBlock = true;
                }
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static Set<String> frontendApiPaths() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (Path dir : List.of(WEB.resolve("lib"), WEB.resolve("app"), WEB.resolve("components"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".ts") && !name.endsWith(".tsx")) {
                        continue;
                    }
                    if (name.endsWith(".d.ts") || name.endsWith(".test.ts")) {
                        // 생성된 타입 정의는 호출이 아니라 스펙 사본이다.
                        continue;
                    }
                    Matcher m = API_PATH.matcher(withoutComments(Files.readString(f)));
                    while (m.find()) {
                        paths.add(stripTemplate(m.group(1)));
                    }
                }
            }
        }
        return paths;
    }

    /** {@code /api/v1/banking/favorites/} 처럼 끝의 슬래시(템플릿 변수 자리)를 지운다. */
    private static String stripTemplate(String raw) {
        String p = raw;
        while (p.length() > 5 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<String> gatewayPathPatterns() throws IOException {
        try (InputStream in = Files.newInputStream(GATEWAY_YML)) {
            Map<String, Object> root = new Yaml().load(in);
            Object cur = root;
            for (String key : List.of("spring", "cloud", "gateway", "routes")) {
                cur = ((Map<String, Object>) cur).get(key);
            }

            List<String> patterns = new ArrayList<>();
            for (Map<String, Object> route : (List<Map<String, Object>>) cur) {
                Object predicates = route.get("predicates");
                if (predicates == null) {
                    continue;
                }
                for (String predicate : (List<String>) predicates) {
                    if (!predicate.startsWith("Path=")) {
                        continue;
                    }
                    for (String p : predicate.substring("Path=".length()).split(",")) {
                        patterns.add(p.strip());
                    }
                }
            }
            return patterns;
        }
    }
}
