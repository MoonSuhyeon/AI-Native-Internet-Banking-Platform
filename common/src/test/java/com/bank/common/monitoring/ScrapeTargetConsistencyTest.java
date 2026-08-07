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
