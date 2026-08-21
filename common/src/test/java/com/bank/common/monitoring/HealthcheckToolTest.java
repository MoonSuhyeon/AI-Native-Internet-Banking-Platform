package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스체크가 그 이미지에 <b>있는</b> 도구를 쓰는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 상담 서비스의 헬스체크가 {@code wget} 을 쓰는데 파이썬
 * 슬림 이미지에는 wget 도 curl 도 없다. 앱은 정상으로 돌고 있는데 컨테이너는
 * <b>영원히 unhealthy</b> 였다.
 *
 * <pre>
 * /bin/sh: 1: wget: not found
 * </pre>
 *
 * <p>조용한 실패가 아니라 <b>번지는</b> 실패다. {@code depends_on: service_healthy}
 * 를 건 컨테이너가 영영 안 뜨고, 원인은 엉뚱한 곳(의존 대상)에서 찾게 된다.
 * 조사 에이전트는 같은 파이썬 이미지인데 이미 {@code python -c} 로 검사하고
 * 있었다 — 한 곳만 예전 방식으로 남아 있었다.
 *
 * <p><b>무엇을 보는가.</b> 파이썬으로 도는 사이드카의 헬스체크가 wget·curl 을
 * 쓰지 않는지만 본다. Java 서비스(eclipse-temurin)에는 wget 이 있어 대상이 아니다.
 * 이미지 안에 실제로 무엇이 설치돼 있는지까지는 파일만 읽어서는 알 수 없다 —
 * 아는 사실 하나를 못 박는 선에서 멈춘다.
 */
class HealthcheckToolTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml").toAbsolutePath().normalize();

    /** 파이썬 이미지로 도는 서비스. 여기에 wget·curl 이 없다. */
    private static final List<String> PYTHON_SERVICES = List.of(
            "consultation-service", "fraud-agent", "goal-agent", "inference-server");

    @Test
    @DisplayName("파이썬 사이드카의 헬스체크가 없는 도구를 부르지 않는다")
    void pythonSidecarsDoNotUseWget() throws IOException {
        String compose = Files.readString(COMPOSE);

        Set<String> broken = new TreeSet<>();
        for (String service : PYTHON_SERVICES) {
            String block = blockOf(compose, service);
            if (block == null) {
                continue;
            }
            for (String line : block.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("test:")) {
                    continue;
                }
                if (t.contains("wget") || t.contains("curl")) {
                    broken.add(service + " → " + t.substring(0, Math.min(t.length(), 70)));
                }
            }
        }

        assertThat(broken)
                .as("파이썬 이미지에는 wget·curl 이 없다. 앱이 멀쩡해도 컨테이너가 "
                    + "영원히 unhealthy 이고, depends_on: service_healthy 를 건 것이 "
                    + "영영 안 뜬다. python -c 로 검사해야 한다")
                .isEmpty();
    }

    /** {@code  <service>:} 부터 같은 들여쓰기의 다음 서비스 전까지. */
    private static String blockOf(String compose, String service) {
        int start = compose.indexOf("\n  " + service + ":\n");
        if (start < 0) {
            return null;
        }
        int next = compose.length();
        for (int i = start + 1; i < compose.length() - 3; i++) {
            if (compose.charAt(i) == '\n'
                    && compose.charAt(i + 1) == ' ' && compose.charAt(i + 2) == ' '
                    && compose.charAt(i + 3) != ' ' && compose.charAt(i + 3) != '#'
                    && i > start + 3) {
                next = i;
                break;
            }
        }
        return compose.substring(start, next);
    }
}
