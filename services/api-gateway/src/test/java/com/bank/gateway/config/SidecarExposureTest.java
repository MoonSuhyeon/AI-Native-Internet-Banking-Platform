package com.bank.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이드카가 호스트에 직접 노출되지 않는지 검증한다.
 *
 * <p><b>왜 코드가 아니라 compose 를 본다.</b> 사이드카의 인가는 "게이트웨이가 주입한
 * 신원 헤더" 위에 서 있다. 그 코드가 아무리 정확해도 포트가 밖에서 닿으면
 * 게이트웨이를 건너뛰고 부르면 그만이라, <b>보호 여부가 배포 설정에 달려 있다</b>.
 *
 * <p>공유 시크릿({@code X-Gateway-Auth})은 그때를 위한 최소 방어선이지 대체재가
 * 아니다. 시크릿이 유출되거나 로컬 편의로 override 에 넣어 둔 값이 그대로 넘어가면
 * 방어선은 사라진다. 근본은 포트를 닫는 것이다.
 *
 * <p>포트를 여는 것은 한 줄이고, 열어도 기능은 멀쩡히 돌기 때문에 되돌아가기 쉽다.
 * 그래서 여기서 못 박는다.
 *
 * <p><b>이 테스트가 보장하지 않는 것.</b> 같은 compose 망 안의 다른 컨테이너는 여전히
 * 사이드카에 닿는다. 그것까지 막으려면 서비스별 내부망 분리가 필요한데,
 * {@code fraud-agent} 만 그렇게 돼 있다({@code fraud-internal}, {@code internal: true}).
 * 나머지는 DB·Kafka·core-banking 과 같은 망을 써야 해서 아직 나누지 못했다 —
 * OPEN_ITEMS 참조.
 */
class SidecarExposureTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        try {
            // 게이트웨이 모듈에서 레포 루트로 올라간다.
            Path compose = Path.of("..", "..", "docker-compose.yml").toAbsolutePath().normalize();
            assertThat(Files.exists(compose))
                    .as("docker-compose.yml 을 찾지 못했다: %s", compose)
                    .isTrue();

            Map<String, Object> root;
            try (var in = Files.newInputStream(compose)) {
                root = new Yaml().load(in);
            }
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            Map<String, Object> svc = (Map<String, Object>) services.get(name);

            assertThat(svc).as("compose 에 서비스 '%s' 가 없다", name).isNotNull();
            return svc;

        } catch (IOException e) {
            throw new IllegalStateException("docker-compose.yml 을 읽지 못했다", e);
        }
    }

    @ParameterizedTest(name = "{0} 은 호스트 포트를 열지 않는다")
    @ValueSource(strings = {
            "consultation-service",   // 고객 개인정보·챗봇 이체
            "doc-agent",              // 심사 결정·법적보존 해제
            "fraud-agent",            // 조사 큐·조사 실행·승인
            "auto-loan-review",       // 심사 결정 로직·ML 추론 (loan-service 만 부른다)
            "review-ai-gateway"})     // 자문 도구 분석 (서비스 간 호출)
    @DisplayName("사이드카는 호스트에 노출되지 않는다 — 열리면 게이트웨이를 건너뛸 수 있다")
    void sidecarDoesNotPublishHostPort(String name) {
        assertThat(service(name).get("ports"))
                .as("'%s' 에 ports 가 생겼다. 포트가 밖에서 닿으면 신원 헤더 기반 인가가 "
                    + "통째로 무의미해진다 — 게이트웨이를 건너뛰고 부르면 그만이다. "
                    + "로컬에서 임시로 열어야 한다면 docker-compose.override.sample.yml 을 쓴다", name)
                .isNull();
    }

    @Test
    @DisplayName("게이트웨이는 사이드카를 컨테이너 이름으로 부른다 — 포트를 닫아도 닿는다")
    @SuppressWarnings("unchecked")
    void gatewayReachesSidecarsByServiceName() {
        Map<String, Object> env =
                (Map<String, Object>) service("api-gateway").get("environment");

        assertThat(env)
                .as("게이트웨이가 사이드카 호스트를 모르면, 포트를 닫는 순간 라우트가 502 가 된다")
                .containsEntry("CONSULTATION_SERVICE_HOST", "consultation-service")
                .containsEntry("DOC_AGENT_HOST", "doc-agent")
                .containsEntry("FRAUD_AGENT_HOST", "fraud-agent");
    }

    @Test
    @DisplayName("사이드카는 기본망에 없다 — 있으면 아무 서비스나 직접 부를 수 있다")
    void sidecarsAreOffTheDefaultNetwork() {
        // 포트를 닫아도 같은 망의 컨테이너는 여전히 닿는다. 기본망에는 41개 서비스가
        // 있어서, 거기 남겨 두면 "게이트웨이를 거쳐야 한다" 는 전제가 사실상 없다.
        //
        // 실물로 확인했다. 분리 후 customer-service 에서 사이드카 이름이 DNS 조회조차
        // 안 되고, 게이트웨이에서는 정상으로 조회된다.
        for (String name : List.of("consultation-service", "doc-agent",
                                   "auto-loan-review", "review-ai-gateway")) {
            assertThat(networksOf(name))
                    .as("'%s' 가 기본망에 있다. 사이드카는 agent-net 에만 둔다", name)
                    .doesNotContain("default");
        }
    }

    @Test
    @DisplayName("X-Gateway-Auth 기본값이 비어 있지 않다 — 비면 게이트웨이가 아예 못 뜬다")
    void gatewayAuthHeaderHasNonEmptyDefault() throws IOException {
        // Spring Cloud Gateway 의 AddRequestHeader 는 값이 비면 바인딩을 거부하고
        // ApplicationContext 가 죽는다. 시크릿 미설정이 기본값이므로, ${VAR:} 로 두면
        // 아무 설정 없이 띄웠을 때 **반드시** 게이트웨이가 못 뜬다.
        //
        // 실제로 그 상태였다. compose 로 올려 보고서야 드러났다.
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml").toAbsolutePath());

        assertThat(yaml)
                .as("AddRequestHeader 값에 빈 기본값(${VAR:})이 있으면 게이트웨이가 죽는다. "
                    + "비어 있지 않은 표식을 쓴다 — 사이드카는 자기 시크릿이 비면 "
                    + "무엇이 오든 믿지 않으므로 보안은 그대로다")
                .doesNotContain("AddRequestHeader=X-Gateway-Auth, ${FRAUD_GATEWAY_SHARED_SECRET:}")
                .doesNotContainPattern("AddRequestHeader=[^\n]*[$][{][A-Z_]+:[}]");
    }

    @SuppressWarnings("unchecked")
    private static List<String> networksOf(String service) {
        Object nets = service(service).get("networks");
        return nets instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("default");
    }

    @Test
    @DisplayName("사이드카마다 공유 시크릿이 게이트웨이와 짝지어져 있다")
    @SuppressWarnings("unchecked")
    void gatewayCarriesEachSidecarSecret() {
        Map<String, Object> env =
                (Map<String, Object>) service("api-gateway").get("environment");

        // 한쪽만 설정되면 사이드카가 조용히 전부 거절한다. 기능이 죽은 것처럼 보이는데
        // 로그에는 "권한 없음" 만 남아 원인을 찾기 어렵다.
        assertThat(env.keySet())
                .contains("CONSULTATION_GATEWAY_SHARED_SECRET",
                          "DOC_AGENT_GATEWAY_SHARED_SECRET",
                          "FRAUD_GATEWAY_SHARED_SECRET");
    }
}
