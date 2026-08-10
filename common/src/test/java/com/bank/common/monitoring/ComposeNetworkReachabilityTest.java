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
 * compose 에서 <b>부르는 서비스가 대상에 실제로 닿는지</b> 검증한다.
 *
 * <p><b>왜 필요한가.</b> 사이드카를 망으로 나누면 외부 노출은 줄지만, 나누다가 한
 * 짝을 빠뜨리면 <b>조용히 끊긴다</b>. 컨테이너는 정상으로 뜨고 헬스체크도 통과하는데
 * 그 서비스로 나가는 호출만 실패한다.
 *
 * <p>그리고 이 레포의 호출부 상당수는 실패를 삼킨다 — 자문 조회는 빈 목록으로,
 * LLM 은 폴백으로, Kafka 는 재시도로 흘러간다. 그래서 끊겨도 화면은 멀쩡하고,
 * 몇 달 뒤에야 "그 기능이 원래 안 됐다" 는 것을 알게 된다. 실제로 이 레포에서
 * advisory-service 가 정확히 그 상태였다.
 *
 * <p>그래서 정적으로 확인한다. 환경변수에 적힌 호스트 이름이 compose 의 서비스라면,
 * 부르는 쪽과 대상은 <b>적어도 하나의 망을 공유해야 한다</b>.
 *
 * <p><b>이 검사의 한계.</b> 환경변수에 드러난 호출만 본다. 코드에 하드코딩된 주소나
 * 설정 파일에만 있는 대상은 잡지 못한다. 그래도 이 레포의 서비스 간 배선은 대부분
 * 환경변수로 주입되므로 실질적인 그물이 된다.
 */
class ComposeNetworkReachabilityTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** {@code http://name:port} 형태. */
    private static final Pattern URL_HOST = Pattern.compile("https?://([A-Za-z0-9_.-]+)");

    /**
     * DB·브로커 접속 문자열의 호스트.
     *
     * <p>{@code jdbc:postgresql://core-banking-db-a:5432/...} 나
     * {@code postgresql+psycopg://user:pw@core-banking-db-a:5432/...} 처럼 http 가
     * 아닌 형태다. 이걸 빼면 DB 배선이 끊겨도 검사가 통과해 <b>헛된 안심</b>을 준다.
     */
    private static final Pattern DSN_HOST =
            Pattern.compile("(?:@|//)([A-Za-z0-9_.-]+):[0-9]+");

    /**
     * 호스트 이름을 담는 환경변수 접미사.
     *
     * <p>URL 이 아니라 이름만 넣는 변수들이다 ({@code AI_DB_HOST: ai-db}).
     */
    private static final Pattern HOST_KEY = Pattern.compile(".*(_HOST|_HOSTNAME|_SERVERS)$");

    @Test
    @DisplayName("환경변수가 가리키는 서비스와 망을 공유한다 — 안 그러면 조용히 끊긴다")
    void everyReferencedServiceShareaNetwork() throws IOException {
        Map<String, Object> root = load(REPO_ROOT.resolve("docker-compose.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) root.get("services");

        Map<String, Set<String>> networks = new LinkedHashMap<>();
        services.forEach((name, def) -> networks.put(name, networksOf(def)));

        List<String> broken = new ArrayList<>();

        services.forEach((name, def) -> {
            for (String target : referencedServices(def, services.keySet())) {
                if (target.equals(name)) {
                    continue;
                }
                Set<String> mine = networks.get(name);
                Set<String> theirs = networks.get(target);
                if (java.util.Collections.disjoint(mine, theirs)) {
                    broken.add(name + " → " + target
                            + " (공유 망 없음: " + mine + " vs " + theirs + ")");
                }
            }
        });

        assertThat(broken)
                .as("부르는 서비스가 대상에 닿지 못한다. 컨테이너는 멀쩡히 뜨고 호출만 "
                    + "실패하는데, 이 레포의 호출부는 실패를 삼키는 곳이 많아 조용히 죽는다.")
                .isEmpty();
    }

    @Test
    @DisplayName("Prometheus 는 자기가 긁는 대상과 망을 공유한다")
    void prometheusSharesNetworkWithScrapeTargets() throws IOException {
        Map<String, Object> compose = load(REPO_ROOT.resolve("docker-compose.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) compose.get("services");

        Set<String> promNets = networksOf(services.get("prometheus"));
        List<String> unreachable = new ArrayList<>();

        for (String target : scrapeHosts()) {
            Map<String, Object> def = services.get(target);
            if (def == null) {
                continue;   // host.docker.internal 등 — 다른 테스트가 본다
            }
            if (java.util.Collections.disjoint(promNets, networksOf(def))) {
                unreachable.add(target);
            }
        }

        assertThat(unreachable)
                .as("스크레이프 대상에 닿지 못하면 대상이 영영 DOWN 이고, 그 위의 "
                    + "대시보드·알림이 조용히 빈다.")
                .isEmpty();
    }

    @Test
    @DisplayName("상시 기동 서비스는 메모리 상한을 갖는다 — 없으면 호스트를 통째로 먹는다")
    void longRunningServicesHaveMemoryLimit() throws IOException {
        // 제한이 없으면 컨테이너가 호스트 메모리를 무제한으로 쓴다. 특히 JVM 서비스는
        // -XX:MaxRAMPercentage=75 를 쓰는데, 이 설정은 컨테이너 제한이 있을 때만
        // 의미가 있다 — 없으면 "호스트 전체의 75%" 가 상한이 되어 안전장치가 아니다.
        //
        // 1회성 초기화 잡은 뺀다. 거기서 메모리로 막히면 스택 전체가 뜨지 않는다.
        Set<String> oneShot = Set.of("payment-topic-init", "kafka-connect-init");

        Map<String, Object> root = load(REPO_ROOT.resolve("docker-compose.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) root.get("services");

        List<String> missing = new ArrayList<>();
        services.forEach((name, def) -> {
            if (oneShot.contains(name)) {
                return;
            }
            if (def.get("mem_limit") == null) {
                missing.add(name);
            }
        });

        assertThat(missing)
                .as("메모리 상한이 없는 서비스. 하나가 폭주하면 다른 서비스까지 함께 "
                    + "느려진다(bulkhead 없음).")
                .isEmpty();
    }

    // ── 파싱 ────────────────────────────────────────────────────────────────

    /** 이 서비스가 붙는 망. 명시가 없으면 compose 기본값인 default 다. */
    private static Set<String> networksOf(Map<String, Object> def) {
        Object nets = def.get("networks");
        if (nets instanceof List<?> list && !list.isEmpty()) {
            Set<String> out = new LinkedHashSet<>();
            list.forEach(n -> out.add(String.valueOf(n)));
            return out;
        }
        if (nets instanceof Map<?, ?> map && !map.isEmpty()) {
            Set<String> out = new LinkedHashSet<>();
            map.keySet().forEach(n -> out.add(String.valueOf(n)));
            return out;
        }
        return Set.of("default");
    }

    /** 환경변수 값에서 compose 서비스 이름을 찾아낸다. */
    private static Set<String> referencedServices(Map<String, Object> def, Set<String> known) {
        Object env = def.get("environment");
        Map<?, ?> map = (env instanceof Map<?, ?> m) ? m : Map.of();
        Set<String> out = new LinkedHashSet<>();
        // depends_on 은 기동 순서 선언이지만, 이 레포에서는 사실상 "이 서비스를 쓴다"
        // 는 뜻으로 쓰인다. 환경변수에 안 드러나는 배선(설정 파일 기본값 등)을
        // 여기서 건진다.
        Object dependsOn = def.get("depends_on");
        if (dependsOn instanceof List<?> list) {
            list.forEach(x -> addIfService(out, String.valueOf(x), known));
        } else if (dependsOn instanceof Map<?, ?> depMap) {
            depMap.keySet().forEach(x -> addIfService(out, String.valueOf(x), known));
        }
        map.forEach((k, v) -> {
            String key = String.valueOf(k);
            String value = String.valueOf(v);

            Matcher m = URL_HOST.matcher(value);
            while (m.find()) {
                addIfService(out, m.group(1), known);
            }
            Matcher dsn = DSN_HOST.matcher(value);
            while (dsn.find()) {
                addIfService(out, dsn.group(1), known);
            }
            if (HOST_KEY.matcher(key).matches()) {
                // "kafka:29092" 처럼 포트가 붙거나 콤마로 여럿일 수 있다.
                for (String part : value.split(",")) {
                    addIfService(out, part.trim().split(":")[0], known);
                }
            }
        });
        return out;
    }

    private static void addIfService(Set<String> out, String host, Set<String> known) {
        // ${VAR:-default} 형태가 남아 있으면 기본값 쪽을 본다.
        String cleaned = host.replaceAll("^\\$\\{[^:}]+:-", "").replaceAll("}$", "");
        if (known.contains(cleaned)) {
            out.add(cleaned);
        }
    }

    /** prometheus.yml 의 대상 중 컨테이너 이름으로 적힌 것. */
    private static Set<String> scrapeHosts() throws IOException {
        Map<String, Object> root = load(REPO_ROOT.resolve("infra/prometheus/prometheus.yml"));
        Set<String> out = new LinkedHashSet<>();
        for (Object o : (List<?>) root.get("scrape_configs")) {
            Map<?, ?> job = (Map<?, ?>) o;
            List<?> statics = (List<?>) job.get("static_configs");
            if (statics == null || statics.isEmpty()) continue;
            List<?> targets = (List<?>) ((Map<?, ?>) statics.get(0)).get("targets");
            if (targets == null) continue;
            for (Object t : targets) {
                String s = String.valueOf(t);
                if (s.startsWith("http")) {
                    Matcher m = URL_HOST.matcher(s);
                    if (m.find()) out.add(m.group(1));
                } else if (s.contains(":")) {
                    out.add(s.substring(0, s.indexOf(':')));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        assertThat(Files.exists(path)).as("%s 가 있어야 한다", path).isTrue();
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
