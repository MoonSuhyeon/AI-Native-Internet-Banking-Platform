package com.bank.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이드카 라우트가 설정에 실제로 있는지 검증한다.
 *
 * <p><b>왜 필터 테스트만으로는 부족한가.</b> {@code JwtAuthenticationFilterTest} 는
 * "요청이 필터를 지나가면 신원이 덮어씌워진다" 를 확인한다. 그런데 라우트가 없으면
 * 요청은 애초에 필터를 지나가지 않는다 — 브라우저가 사이드카(상담 8087, 조사 8090)를
 * 직접 부르고, 신원은 아무도 검증하지 않는다.
 *
 * <p>그 상태가 특히 나쁜 이유는 <b>조용하기 때문</b>이다. 필터 테스트는 전부 통과하고,
 * 화면도 동작하며, 감사 로그도 쌓인다. 다만 그 로그의 행위자가 자칭일 뿐이다.
 * 실제로 상담 쪽이 오랫동안 그 상태였다 — 코드와 주석은 "JWT 에서 추출해 사칭을
 * 방지한다" 고 말하고 있었지만, 라우트가 없어 토큰이 도착한 적이 없었다.
 *
 * <p>그래서 설정 파일 자체를 읽어 확인한다. 라우트를 지우거나 경로가 어긋나면 여기서
 * 걸린다.
 *
 * <p><b>이 테스트가 보장하지 않는 것.</b> 사이드카 포트가 외부에 열려 있으면
 * 게이트웨이를 우회하면 그만이다. 네트워크 격리는 배포 설정의 몫이고 여기서 볼 수 없다.
 * 공유 시크릿({@code X-Gateway-Auth})은 그때까지의 최소 방어선이라 함께 확인한다.
 */
class InternalRouteConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> route(String id) {
        try (InputStream in = InternalRouteConfigTest.class
                .getResourceAsStream("/application.yml")) {

            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> gateway = (Map<String, Object>) nested(
                    root, "spring", "cloud", "gateway");
            List<Map<String, Object>> routes =
                    (List<Map<String, Object>>) gateway.get("routes");

            Optional<Map<String, Object>> found = routes.stream()
                    .filter(r -> id.equals(r.get("id")))
                    .findFirst();

            assertThat(found)
                    .as("라우트 '%s' 가 없다. 사이드카를 브라우저가 직접 부르게 되고, "
                        + "그러면 X-Employee-Id 를 손으로 붙여도 막을 방법이 없다", id)
                    .isPresent();
            return found.get();

        } catch (Exception e) {
            throw new IllegalStateException("application.yml 을 읽지 못했다", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map, String... keys) {
        Object cur = map;
        for (String k : keys) {
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> route, String key) {
        Object v = route.get(key);
        return v == null ? List.of() : (List<String>) v;
    }

    @ParameterizedTest(name = "{0} 는 {1} 로 들어온다")
    @CsvSource({
            "consultation-staff,    /api/v1/internal/consultation/**",
            "consultation-customer, /api/v1/consultation/**",
            "fraud-agent,           /api/v1/internal/fraud/**",
    })
    @DisplayName("사이드카는 게이트웨이 경로로 노출된다 — 없으면 브라우저가 직접 부른다")
    void sidecarRouteIsExposedThroughGateway(String id, String expectedPath) {
        assertThat(strings(route(id), "predicates"))
                .as("경로가 바뀌면 프론트는 404 를 받고, 예전 직통 경로로 되돌아가기 쉽다")
                .contains("Path=" + expectedPath);
    }

    @ParameterizedTest(name = "{0} 는 X-Gateway-Auth 를 붙인다")
    @CsvSource({
            "consultation-staff,    CONSULTATION_GATEWAY_SHARED_SECRET",
            "consultation-customer, CONSULTATION_GATEWAY_SHARED_SECRET",
            "fraud-agent,           FRAUD_GATEWAY_SHARED_SECRET",
    })
    @DisplayName("게이트웨이를 거쳤다는 증거를 붙인다 — 사이드카는 이 값이 맞을 때만 신원을 믿는다")
    void sidecarRouteAttachesGatewayProof(String id, String secretEnv) {
        assertThat(strings(route(id), "filters"))
                .as("이 헤더가 없으면 사이드카는 신원 헤더를 전부 무시한다(fail-closed). "
                    + "즉 라우트는 있는데 직원 기능이 통째로 거절되는 상태가 된다")
                .anyMatch(f -> f.startsWith("AddRequestHeader=X-Gateway-Auth")
                            && f.contains(secretEnv));
    }

    @ParameterizedTest(name = "{0} 는 접두어를 떼고 전달한다")
    @CsvSource({
            "consultation-staff,    /api/v1/internal/consultation/",
            "consultation-customer, /api/v1/consultation/",
    })
    @DisplayName("상담 경로는 접두어를 떼고 전달된다 — 상담 서비스는 /chatbot/... 로 받는다")
    void consultationRouteRewritesPrefix(String id, String prefix) {
        assertThat(strings(route(id), "filters"))
                .as("재작성이 없으면 상담 서비스가 접두어까지 붙은 경로를 받아 404 를 준다")
                .anyMatch(f -> f.startsWith("RewritePath=" + prefix));
    }

    @Test
    @DisplayName("고객 경로와 직원 경로는 따로 있다 — 합치면 어느 신원으로 볼지 서비스가 정해야 한다")
    void customerAndStaffRoutesAreSeparate() {
        // 게이트웨이가 주입하는 신원이 다르다(X-Customer-Id 대 X-Employee-Id).
        // 한 경로로 합치면 상담 서비스가 스스로 갈라야 하고, 그 판단이 들어가는
        // 순간 잘못 갈리는 경우가 생긴다.
        assertThat(route("consultation-customer").get("id"))
                .isNotEqualTo(route("consultation-staff").get("id"));
    }

    @Test
    @DisplayName("사이드카 경로는 공개 경로가 아니다 — 공개면 JWT 없이 통과한다")
    void sidecarPathsAreNotPublic() {
        // 공개 목록에 들어가면 필터가 그대로 통과시켜, 클라이언트가 붙인
        // X-Employee-Id 가 지워지지도 덮어씌워지지도 않는다.
        assertThat(List.of(
                "/api/v1/internal/consultation/chatbot/features/STAFF_CUSTOMER/execute",
                "/api/v1/consultation/chatbot/transfer",
                "/api/v1/internal/fraud/investigate"))
                .allSatisfy(path -> assertThat(path)
                        .doesNotStartWith("/api/v1/auth/")
                        .doesNotStartWith("/api/v1/mobile-auth/")
                        .doesNotStartWith("/actuator/"));
    }
}
