package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조 문서가 실제 구성과 어긋나지 않게 한다.
 *
 * <p><b>왜 필요한가.</b> 지운 {@code architecture.svg} 는 낡은 정도가 아니라 <b>없는
 * 것을 그리고 있었다</b> — {@code deposit-service}·{@code payment-service}(병합됨)·
 * {@code master-service}·{@code ai-service}·{@code advisory-service}. 반대로 사이드카
 * 넷과 망 격리, 상담 전용 DB 는 없었다.
 *
 * <p>그런데 아무도 몰랐다. 그림은 아무것도 실행하지 않으므로 틀려도 깨지지 않는다.
 * 사람이 눈으로 대조하는 수밖에 없고, 그 일은 결국 안 하게 된다.
 *
 * <p>그래서 여기서 센다. 서비스를 더하거나 지우면 문서도 함께 고쳐야 빌드가 통과한다.
 * 그림을 그리는 일이 아니라 <b>사실과 맞추는 일</b>이 강제된다.
 *
 * <p><b>이 테스트가 보장하지 않는 것.</b> 선이 맞는지는 보지 못한다 — 어느 서비스가
 * 어느 서비스를 부르는지, 신뢰 경계가 제자리인지는 사람이 봐야 한다. 여기서 막는 것은
 * "있지도 않은 것이 그려져 있는" 종류의 어긋남이다.
 */
@DisplayName("구조 문서 — 없는 것을 그리지 않는다")
class ArchitectureDiagramFreshnessTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DOC = REPO_ROOT.resolve("docs/architecture.md");

    /**
     * 문서에 이름이 없어도 되는 서비스.
     *
     * <p>구조를 이해하는 데 보탬이 되지 않는 것들이다 — 지표 수집기·1회성 초기화 잡·
     * 로그 수송기. 이것까지 그리면 그림이 배선도가 되고, 배선도는 아무도 안 읽는다.
     *
     * <p>기준은 "이것이 빠지면 구조를 오해하는가" 다. 오해하지 않으면 빼도 된다.
     */
    private static final Set<String> NOT_ON_DIAGRAM = Set.of(
            "prometheus", "grafana", "loki", "promtail", "alertmanager",
            "blackbox-exporter", "kafka-exporter-kftc", "kafka-exporter-bok",
            "kafka-exporter-internal", "schema-registry", "kafka-connect",
            "kafka-connect-init", "payment-topic-init", "mock-responder",
            "phoenix", "redis", "minio", "vault", "elasticsearch", "kibana",
            // DB 는 3장(데이터 등급) 표에서 따로 센다.
            "customer-db", "core-banking-db-a", "core-banking-db-b", "loan-db",
            "common-db", "ai-db", "doc-agent-db", "consultation-db");

    @Test
    @DisplayName("compose 에 있는 서비스가 문서에도 있다")
    void every_service_appears_in_the_document() throws Exception {
        // 대소문자는 보지 않는다. compose 의 이름은 소문자지만 글에서는 Kafka 처럼
        // 자연스럽게 쓴다. 표기까지 맞추라고 하면 문서가 읽기 나빠질 뿐이다.
        String doc = Files.readString(DOC).toLowerCase();

        Set<String> missing = new TreeSet<>();
        for (String service : composeServices()) {
            if (NOT_ON_DIAGRAM.contains(service)) {
                continue;
            }
            if (!doc.contains(service.toLowerCase())) {
                missing.add(service);
            }
        }

        assertThat(missing)
                .as("compose 에 있는데 구조 문서에 없다. 새 서비스를 넣었으면 그림도 고칠 것.\n"
                        + "구조 이해에 필요 없는 것이면 NOT_ON_DIAGRAM 에 이유와 함께 적는다.")
                .isEmpty();
    }

    @Test
    @DisplayName("문서가 없는 서비스를 그리지 않는다")
    void document_does_not_mention_removed_services() throws Exception {
        String doc = Files.readString(DOC);
        Set<String> actual = composeServices();

        // 실제로 사라진 것들이다. 되살아나면 이 목록에서 빼는 것이 아니라
        // compose 에 다시 생기므로 아래 검사가 알아서 통과한다.
        List<String> gone = List.of(
                "deposit-service", "payment-service", "master-service",
                "ai-service", "advisory-service", "gateway-service");

        Set<String> resurrected = new TreeSet<>();
        for (String name : gone) {
            if (actual.contains(name)) {
                continue;   // 실제로 다시 생겼다면 그리는 것이 맞다
            }
            // "지운 이유" 절에서 옛 이름을 설명하는 것은 허용한다.
            // 그 문단 밖에서 쓰이면 아직 있는 것처럼 읽힌다.
            String beforeExplanation = doc.substring(0, doc.indexOf("## 옛 그림을 지운 이유"));
            if (beforeExplanation.contains(name)) {
                resurrected.add(name);
            }
        }

        assertThat(resurrected)
                .as("없어진 서비스가 아직 있는 것처럼 그려져 있다. "
                        + "틀린 그림은 없는 그림보다 나쁘다 — 읽는 사람이 근거로 삼기 때문이다.")
                .isEmpty();
    }

    @Test
    @DisplayName("격리망이 문서에 그대로 적혀 있다")
    void internal_networks_are_documented() throws Exception {
        String doc = Files.readString(DOC);

        for (String network : internalNetworks()) {
            assertThat(doc)
                    .as("'%s' 은 internal: true 인데 구조 문서에 없다. "
                            + "닿을 수 있음과 없음이 이 그림의 요점이다.", network)
                    .contains(network);
            assertThat(doc)
                    .as("'%s' 이 격리망이라는 사실이 문서에 없다", network)
                    .contains("internal: true");
        }
    }

    @Test
    @DisplayName("옛 SVG 는 남아 있지 않다")
    void stale_svg_is_gone() {
        // 지운 그림이 되돌아오면 두 벌이 되고, 둘 중 하나는 반드시 낡는다.
        assertThat(REPO_ROOT.resolve("docs/architecture.svg"))
                .as("옛 구조 그림이 되살아났다. 정본은 docs/architecture.md 하나여야 한다.")
                .doesNotExist();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> composeServices() throws Exception {
        Map<String, Object> root = load(REPO_ROOT.resolve("docker-compose.yml"));
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        assertThat(services).as("compose 에서 services 를 읽지 못했다").isNotNull();
        return new TreeSet<>(services.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> internalNetworks() throws Exception {
        Map<String, Object> root = load(REPO_ROOT.resolve("docker-compose.yml"));
        Map<String, Object> networks = (Map<String, Object>) root.get("networks");
        Set<String> internal = new TreeSet<>();
        if (networks == null) {
            return internal;
        }
        networks.forEach((name, spec) -> {
            if (spec instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("internal"))) {
                internal.add(name);
            }
        });
        assertThat(internal).as("격리망이 하나도 없다 — compose 를 잘못 읽었을 것이다").isNotEmpty();
        return internal;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws Exception {
        assertThat(path).as("파일을 찾지 못했다: %s", path).exists();
        try (var in = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }
}
