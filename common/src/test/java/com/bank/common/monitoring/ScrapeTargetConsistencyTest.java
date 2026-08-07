package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus 스크레이프 대상이 실제로 존재하는 서비스를 가리키는지 검사한다.
 *
 * <p><b>왜 필요한가.</b> 모니터링은 끊겨도 아무도 에러를 내지 않는다. compose 에서 포트를
 * 안 열거나, 서비스 이름이 바뀌거나, 잡 대상이 낡아도 빌드는 통과하고 테스트도 통과한다.
 * Prometheus UI 를 열어 DOWN 을 봐야만 드러난다.
 *
 * <p>실제로 그렇게 끊겨 있었다. auto-loan-review 는 compose 에 {@code ports} 가 없어
 * 호스트로 지표가 나오지 않았고, 그 결과 AI 심사 지표(ai_agent_*)가 하나도 수집되지 않았다.
 * 컨테이너 안에서는 정상 노출됐기 때문에 코드만 봐서는 알 수 없었다.
 * 그 위에 얹힌 Grafana 패널 13종과 알림 3종(폴백률·하드페일·불일치율)이 빈 채로 돌았다 —
 * 알림이 안 울리는 것이 특히 나쁘다. "이상 없음"과 "관측 불가"가 구분되지 않는다.
 *
 * <p>그래서 설정 두 파일이 서로 맞는지 여기서 못박는다. 실행 중인 스택이 필요 없다.
 *
 * <p><b>검사 범위.</b> {@code host.docker.internal:<포트>} 대상만 본다. 그 형태는 "컨테이너가
 * 호스트에 게시한 포트"를 뜻하므로 compose 의 ports 와 대조할 수 있다. compose 네트워크
 * 내부 이름을 쓰는 대상(grafana:3000 등)은 서비스 존재 여부만 본다.
 */
class ScrapeTargetConsistencyTest {

    /** 테스트 작업 디렉터리는 common/ 이라 레포 루트는 한 단계 위다. */
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    /**
     * compose 에 없지만 호스트에서 따로 띄우는 대상.
     * windows-exporter 는 OS 지표 수집기로 호스트에 설치해 돌린다(.env.sample 의
     * WINDOWS_EXPORTER_PORT). compose 로 관리하지 않으므로 대조 대상이 아니다.
     */
    private static final Set<String> EXTERNAL_JOBS = Set.of("windows-exporter", "prometheus");

    /**
     * 정규식 안의 역슬래시 하나.
     *
     * <p>자바 문자열과 정규식이 각각 이스케이프를 먹어 {@code "\\\\?"} 처럼 겹치는데,
     * 그러다 한 겹을 놓치면 {@code \?}(물음표 리터럴)가 되어 아무것도 매칭하지 않는다.
     * 실제로 그렇게 써서 죽은 라벨을 못 잡는 테스트가 나왔다 — 상수로 빼 눈에 보이게 한다.
     */
    private static final String BACKSLASH = "\\\\";

    @Test
    @DisplayName("모든 스크레이프 대상이 compose 가 게시하는 포트를 가리킨다")
    void everyTargetMapsToAPublishedPort() throws IOException {
        Map<String, List<String>> publishedPorts = publishedHostPorts();
        List<String> broken = new ArrayList<>();

        for (Map.Entry<String, String> job : scrapeTargets().entrySet()) {
            String name = job.getKey();
            String target = job.getValue();
            if (EXTERNAL_JOBS.contains(name) || !target.startsWith("host.docker.internal:")) {
                continue;
            }
            String port = target.substring(target.lastIndexOf(':') + 1).replaceAll("/.*", "");
            if (!publishedPorts.containsKey(port)) {
                broken.add(name + " → " + target + " (이 포트를 게시하는 compose 서비스가 없다)");
            }
        }

        assertThat(broken)
                .as("죽은 스크레이프 대상. 늘 DOWN 인 대상은 진짜 장애를 가린다.")
                .isEmpty();
    }

    @Test
    @DisplayName("한 호스트 포트를 두 서비스가 게시하지 않는다")
    void noHostPortCollision() throws IOException {
        List<String> collisions = new ArrayList<>();
        publishedHostPorts().forEach((port, services) -> {
            if (services.size() > 1) {
                collisions.add(port + " ← " + services);
            }
        });

        assertThat(collisions)
                .as("같은 호스트 포트를 두 서비스가 쓰면 나중에 뜨는 쪽이 죽는다. "
                    + "프로필이 달라 평소에 안 드러나도 함께 띄우는 순간 터진다.")
                .isEmpty();
    }

    @Test
    @DisplayName("지표를 내는 서비스는 스크레이프 잡을 갖는다")
    void metricsExposingServicesAreScraped() throws IOException {
        // 액추에이터 프로메테우스를 노출하는 JVM 서비스들. 지표를 내는데 긁지 않으면
        // 만들어만 두고 보지 않는 셈이다.
        Set<String> mustBeScraped = Set.of(
                "api-gateway", "customer-service", "core-banking-a", "core-banking-b",
                "loan-service", "auto-loan-review", "review-ai-gateway",
                "consultation-service", "doc-agent");

        Set<String> jobs = scrapeTargets().keySet();
        Set<String> missing = new LinkedHashSet<>(mustBeScraped);
        missing.removeAll(jobs);

        assertThat(missing)
                .as("스크레이프 잡이 없는 서비스. 잡 이름은 compose 서비스 이름과 맞춘다.")
                .isEmpty();
    }

    @Test
    @DisplayName("대시보드·알림이 거는 application 라벨이 실제 서비스 이름이다")
    void dashboardApplicationLabelsExist() throws IOException {
        // Micrometer 의 application 라벨은 spring.application.name 에서 온다.
        // 서비스 이름이 바뀌면 그 라벨을 걸던 패널은 조용히 빈다 — 쿼리는 유효하고 결과만 0건이다.
        // 실제로 수신·결제 병합 후에도 kafka-payment 대시보드가 application="payment-service" 를
        // 19곳에서 보고 있어 결제 패널이 전부 비어 있었다.
        Set<String> known = applicationNames();
        Set<String> used = new LinkedHashSet<>();
        // JSON 안에서는 application=\"이름\" 으로 이스케이프되고, YAML 에서는 application="이름" 이다.
        // 그래서 따옴표 앞 역슬래시를 선택적으로 둔다.
        Pattern label = Pattern.compile("application=" + BACKSLASH + "?\"([a-z][a-z0-9-]*)" + BACKSLASH + "?\"");
        for (Path f : monitoringFiles()) {
            Matcher m = label.matcher(Files.readString(f));
            while (m.find()) used.add(m.group(1));
        }

        Set<String> unknown = new LinkedHashSet<>(used);
        unknown.removeAll(known);

        assertThat(unknown)
                .as("존재하지 않는 application 라벨. 이 라벨을 거는 패널·알림은 항상 0건이다. "
                    + "알려진 이름: %s", known)
                .isEmpty();
    }

    /**
     * 지표에 실제로 붙는 application 라벨 값의 집합.
     *
     * <p>두 경로가 있다.
     * <ul>
     *   <li>JVM 서비스 — Micrometer 가 {@code spring.application.name} 을 붙인다.
     *       yml 에서 4칸 들여쓰기 아래의 {@code name:} 이다(spring → application → name).</li>
     *   <li>파이썬 서비스 — 그런 장치가 없어 Prometheus 잡의 {@code labels} 로 달아준다.
     *       consultation-service 가 그렇다.</li>
     * </ul>
     *
     * <p>빌드 산출물은 제외한다 — 옛 이름이 남아 있으면 죽은 라벨이 살아 있는 것처럼 보인다.
     */
    private Set<String> applicationNames() throws IOException {
        Set<String> names = new LinkedHashSet<>(scrapeJobApplicationLabels());
        Pattern p = Pattern.compile("^ {4}name: *([a-z][a-z0-9-]*) *$", Pattern.MULTILINE);
        try (var paths = Files.walk(REPO_ROOT)) {
            for (Path f : paths.filter(x -> x.endsWith("application.yml"))
                    .filter(x -> {
                        String u = x.toString().replace('\\', '/');
                        return !u.contains("/build/") && !u.contains("/bin/");
                    })
                    .toList()) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) names.add(m.group(1));
            }
        }
        return names;
    }

    /** Prometheus 잡이 static_configs.labels 로 직접 붙이는 application 값. */
    private Set<String> scrapeJobApplicationLabels() throws IOException {
        Map<String, Object> root = load(REPO_ROOT.resolve("infra/prometheus/prometheus.yml"));
        Set<String> out = new LinkedHashSet<>();
        for (Object o : (List<?>) root.get("scrape_configs")) {
            List<?> statics = (List<?>) ((Map<?, ?>) o).get("static_configs");
            if (statics == null) continue;
            for (Object sc : statics) {
                Map<?, ?> labels = (Map<?, ?>) ((Map<?, ?>) sc).get("labels");
                if (labels != null && labels.get("application") != null) {
                    out.add(String.valueOf(labels.get("application")));
                }
            }
        }
        return out;
    }

    private List<Path> monitoringFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        out.add(REPO_ROOT.resolve("infra/prometheus/alerts.yml"));
        Path dash = REPO_ROOT.resolve("infra/grafana/provisioning/dashboards");
        try (var paths = Files.walk(dash)) {
            paths.filter(f -> f.toString().endsWith(".json")).forEach(out::add);
        }
        return out;
    }

    // ── 파싱 ──────────────────────────────────────────────────────────────────

    /** job_name → 첫 번째 target. */
    private Map<String, String> scrapeTargets() throws IOException {
        Map<String, Object> root = load(REPO_ROOT.resolve("infra/prometheus/prometheus.yml"));
        Map<String, String> out = new LinkedHashMap<>();
        for (Object o : (List<?>) root.get("scrape_configs")) {
            Map<?, ?> job = (Map<?, ?>) o;
            List<?> statics = (List<?>) job.get("static_configs");
            if (statics == null || statics.isEmpty()) continue;
            List<?> targets = (List<?>) ((Map<?, ?>) statics.get(0)).get("targets");
            if (targets == null || targets.isEmpty()) continue;
            out.put(String.valueOf(job.get("job_name")), String.valueOf(targets.get(0)));
        }
        return out;
    }

    /** 호스트 포트 → 그 포트를 게시하는 서비스 이름들. */
    private Map<String, List<String>> publishedHostPorts() throws IOException {
        Map<String, Object> root = load(REPO_ROOT.resolve("docker-compose.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) root.get("services");

        // "${VAR:-8089}:8089" 또는 "8089:8089" 에서 호스트 쪽 포트를 뽑는다.
        Pattern p = Pattern.compile("^(?:\\$\\{[A-Z_]+:-(\\d+)}|(\\d+)):\\d+$");
        Map<String, List<String>> out = new LinkedHashMap<>();
        services.forEach((name, def) -> {
            Object ports = def.get("ports");
            if (!(ports instanceof List<?> list)) return;
            for (Object entry : list) {
                Matcher m = p.matcher(String.valueOf(entry));
                if (!m.matches()) continue;
                String host = m.group(1) != null ? m.group(1) : m.group(2);
                out.computeIfAbsent(host, k -> new ArrayList<>()).add(name);
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(Path path) throws IOException {
        assertThat(Files.exists(path)).as("%s 가 있어야 한다", path).isTrue();
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
