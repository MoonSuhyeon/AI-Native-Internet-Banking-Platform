package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 명세가 소스와 어긋나지 않는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code docs/api-spec.md} 는 "소스 컨트롤러에서 자동 추출"
 * 이라고 적혀 있었지만 추출기가 레포에 없었다. 손으로 관리되다가 서비스 병합을
 * 반영하지 못했고, 결과적으로 <b>문서를 보고 붙이면 없는 서비스를 부르게</b> 되는
 * 상태였다 — {@code deposit-service}(8082)·{@code payment-service}(8080)는
 * {@code core-banking} 으로 합쳐졌고, {@code advisory-service} 는 loan-service 안에
 * 있으며, {@code master-service} 는 존재한 적이 없다.
 *
 * <p>문서만 한 번 고치면 다음 병합 때 또 어긋난다. 그래서 여기서 못 박는다.
 *
 * <p><b>무엇을 보는가.</b> 두 가지다.
 *
 * <ol>
 *   <li>문서에 <b>없는 서비스 이름</b>이 서비스로 소개되지 않는가</li>
 *   <li>소스의 컨트롤러 경로가 문서에 <b>빠져 있지 않은가</b></li>
 * </ol>
 *
 * <p>반대 방향(문서에만 있고 소스에 없는 경로)은 보지 않는다. 문서에는 설명을 위한
 * 예시 경로가 섞이므로 그쪽까지 막으면 거짓 경보가 난다. 빠뜨리는 쪽이 실제로
 * 사람을 헛수고시키는 방향이다.
 */
class ApiSpecFreshnessTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path SPEC = ROOT.resolve("docs").resolve("api-spec.md");

    /** 지금 없는 것들. 문서가 이것들을 <b>서비스로</b> 소개하면 잘못이다. */
    private static final List<String> GONE_SERVICES =
            List.of("deposit-service", "payment-service", "advisory-service", "master-service");

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping\\s*(?:\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"|\\()?");
    private static final Pattern CONTEXT_PATH = Pattern.compile("context-path:\\s*(\\S+)");

    /** Java 서비스 모듈. 추출 스크립트의 목록과 같아야 한다. */
    private static final List<String> MODULES = List.of(
            "services/customer-service",
            "services/core-banking",
            "services/loan-service",
            "services/fds-detector",
            "agents/auto-loan-review",
            "agents/review-ai-gateway",
            "agents/doc-agent");

    @Test
    @DisplayName("문서가 없는 서비스를 서비스로 소개하지 않는다")
    void noGhostServices() throws IOException {
        String doc = Files.readString(SPEC);

        Set<String> ghosts = new TreeSet<>();
        for (String name : GONE_SERVICES) {
            // 목차·절 제목에 나오면 "이런 서비스가 있다" 로 읽힌다. 본문에서 과거를
            // 설명하며 언급하는 것은 막지 않는다 — 무엇이 바뀌었는지 적는 편이 낫다.
            if (doc.contains("## " + name) || doc.contains("](#" + name + ")")) {
                ghosts.add(name);
            }
        }

        assertThat(ghosts)
                .as("문서가 지금 없는 서비스를 절/목차로 소개한다. "
                    + "이 문서를 보고 붙이면 없는 주소를 부르게 된다. "
                    + "python scripts/extract_api_spec.py 로 다시 써야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("소스의 컨트롤러 경로가 문서에 빠져 있지 않다")
    void everyControllerPathIsDocumented() throws IOException {
        String doc = Files.readString(SPEC);

        Set<String> missing = new TreeSet<>();
        for (String module : MODULES) {
            for (String path : pathsOf(ROOT.resolve(module))) {
                if (!doc.contains("`" + path + "`")) {
                    missing.add(path);
                }
            }
        }

        assertThat(missing)
                .as("소스에 있는데 문서에 없는 경로다. 새 API 를 만들고 문서를 다시 쓰지 "
                    + "않으면 다음 사람이 그 API 가 있는지 모른다. "
                    + "python scripts/extract_api_spec.py 를 돌려야 한다")
                .isEmpty();
    }

    private static Set<String> pathsOf(Path module) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        Path src = module.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(src)) {
            return paths;
        }
        String ctx = contextPath(module);

        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                if (!text.contains("@RestController")) {
                    continue;
                }
                String base = "";
                Matcher cm = CLASS_MAPPING.matcher(text);
                if (cm.find()) {
                    base = cm.group(1);
                }
                Matcher mm = METHOD_MAPPING.matcher(text);
                while (mm.find()) {
                    String path = mm.group(2) == null ? "" : mm.group(2);
                    paths.add(ctx + join(base, path));
                }
            }
        }
        return paths;
    }

    private static String contextPath(Path module) throws IOException {
        Path yml = module.resolve("src").resolve("main").resolve("resources").resolve("application.yml");
        if (!Files.exists(yml)) {
            return "";
        }
        Matcher m = CONTEXT_PATH.matcher(Files.readString(yml));
        return m.find() ? m.group(1).trim().replaceAll("/+$", "") : "";
    }

    private static String join(String base, String path) {
        if (base.isEmpty()) {
            return path.isEmpty() ? "/" : path;
        }
        if (path.isEmpty()) {
            return base;
        }
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }
}
