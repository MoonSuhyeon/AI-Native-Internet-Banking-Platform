package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트가 스스로 토큰을 만들거나 비밀번호를 대조하지 않는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code web/app/api/} 아래에 <b>인증하지 않는 인증 경로</b>가
 * 여섯 개 있었다. 백엔드를 부르지 않고 하드코딩된 계정·PIN 과 대조해
 * {@code mock.<base64>.<시각>} 형태의 가짜 토큰을 내줬다.
 *
 * <pre>
 * /api/auth/cert-login   cert_1 / 123456      → 가짜 토큰
 * /api/auth/login        하드코딩 이메일·비밀번호 → 가짜 토큰
 * /api/v1/auth/login     백엔드 3초 무응답 시 폴백 → 가짜 토큰
 * /api/auth/qr(-cert)/*  고정 토큰 상수
 * </pre>
 *
 * <p><b>두 가지가 나빴다.</b>
 *
 * <ol>
 *   <li><b>인증하지 않는 인증 경로다.</b> 백엔드가 잠깐 느리기만 해도 하드코딩된
 *       비밀번호로 들어올 수 있었다. 실제 시드의 비밀번호와 다른 값이라
 *       "백엔드가 살아 있을 때는 안 되고 죽었을 때만 되는" 계정이었다.</li>
 *   <li><b>가짜 토큰은 게이트웨이가 거절한다.</b> 화면만 로그인된 것처럼 보이고
 *       이후 모든 API 가 401 이다 — 원인을 찾기 가장 어려운 상태다.</li>
 * </ol>
 *
 * <p>백엔드가 없으면 로그인은 <b>실패해야 한다.</b> 그것이 정직한 동작이다.
 *
 * <p><b>무엇을 보는가.</b> 프록시 라우트가 토큰을 조립하거나 자격증명을 자기 안에서
 * 대조하는 흔적만 본다. 프록시가 상류 응답을 그대로 흘려보내는 것은 정상이다.
 */
class NoMockAuthRouteTest {

    private static final Path API_DIR =
            Path.of("..", "web", "app", "api").toAbsolutePath().normalize();

    /** 라우트가 직접 토큰을 만들거나 자격증명을 들고 있다는 신호. */
    private static final List<String> FORBIDDEN = List.of(
            "MOCK_USERS", "MOCK_CERTS", "MOCK_CUSTOMERS", "MOCK_ACCESS_TOKEN",
            "mock_refresh", "`mock.", "'mock.");

    /**
     * 주석을 걷어낸다.
     *
     * <p>없앤 동작을 <b>주석으로 설명하는 것</b>은 지워야 할 코드가 아니라 남겨야 할
     * 기록이다. 걷어내지 않으면 "가짜 토큰을 없앴다" 고 적은 주석 자체가 검사에
     * 걸려, 설명을 지우게 만든다.
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
            if (trimmed.startsWith("/*")) {
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

    @Test
    @DisplayName("프론트 API 라우트가 토큰을 스스로 만들지 않는다")
    void noRouteMintsItsOwnToken() throws IOException {
        if (!Files.isDirectory(API_DIR)) {
            return;
        }

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(API_DIR)) {
            for (Path f : files.filter(p -> p.getFileName().toString().equals("route.ts")).toList()) {
                String text = withoutComments(Files.readString(f));
                for (String marker : FORBIDDEN) {
                    if (text.contains(marker)) {
                        offenders.add(API_DIR.relativize(f) + " → " + marker);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("프론트 라우트가 백엔드를 거치지 않고 토큰을 만들거나 자격증명을 "
                    + "대조한다. 인증하지 않는 인증 경로이고, 그렇게 만든 토큰은 "
                    + "게이트웨이가 거절해 '로그인은 됐는데 아무것도 안 되는' 상태가 된다. "
                    + "백엔드가 없으면 로그인은 실패해야 한다")
                .isEmpty();
    }
}
