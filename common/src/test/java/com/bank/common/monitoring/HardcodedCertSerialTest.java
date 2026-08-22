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
 * 프론트 코드에 인증서 일련번호가 박혀 있지 않은지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 로그인 화면과 챗봇이 인증서 일련번호를 상수로 들고
 * 있었다. {@code FINCERT-TEST-2024-000001} 과 {@code COMMON-TEST-2024-000001} 이다.
 *
 * <p>둘 다 <b>고객 9001 의 인증서인데, 9001 은 직원(지점장)이다.</b> 그래서
 * 인증서 로그인은 성공하지만 발급된 토큰에 {@code ROLE_CUSTOMER} 가 없고,
 * 게이트웨이의 {@code CUSTOMER_ONLY_PATHS} 가드가 {@code /api/v1/customers/me} 를
 * 403 으로 막는다. 겉으로 나타난 증상은 <b>"로그인은 되는데 마이페이지만 안
 * 뜬다"</b> 였다 — 원인에서 두 단계 떨어진 화면이다.
 *
 * <p><b>상수를 고객 인증서로 바꾸는 것은 답이 아니다.</b> 그러면 증상만 옮겨
 * 간다. 방문자 아무나 PIN 여섯 자리만 맞히면 <b>남의 계정</b>으로 들어온다.
 * 인증서는 이 브라우저에서 발급받은 것을 써야 하고, 그 값은
 * {@code lib/issued-cert.ts} 가 발급 화면이 저장해 둔 데서 읽는다.
 *
 * <p><b>어떻게 보는가.</b> 문자열 리터럴 안만 본다. 주석은 제외한다 — 위 사고를
 * 설명하는 주석들이 옛 일련번호를 그대로 인용하고 있고, 그건 지워야 할 대상이
 * 아니라 남겨야 할 기록이다.
 *
 * <p><b>보지 않는 것.</b> 백엔드 테스트 픽스처는 대상이 아니다. 거기서 일련번호를
 * 쓰는 것은 정상이다. 여기서 막는 것은 <b>운영 화면이 특정인의 인증서를 들고
 * 있는</b> 한 종류다.
 */
class HardcodedCertSerialTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();

    /** 실제 시드에 있는 인증서 일련번호의 앞머리. */
    private static final List<String> SERIAL_PREFIXES = List.of(
            "FINCERT-", "COMMON-TEST-", "AXFUL-TEST-");

    /** 발급받은 인증서를 읽는 곳. 키 이름을 다루므로 여기는 예외다. */
    private static final String READER = "lib/issued-cert.ts";

    @Test
    @DisplayName("화면 코드에 인증서 일련번호가 박혀 있지 않다")
    void noScreenCarriesSomeonesCertificate() throws IOException {
        Set<String> offenders = new TreeSet<>();

        for (String dir : List.of("app", "components", "lib")) {
            Path root = WEB.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".ts") && !name.endsWith(".tsx")) {
                        continue;
                    }
                    String rel = WEB.relativize(f).toString().replace('\\', '/');
                    if (rel.equals(READER)) {
                        continue;
                    }
                    String literals = TsSource.stringLiterals(Files.readString(f));
                    for (String prefix : SERIAL_PREFIXES) {
                        if (literals.contains(prefix)) {
                            offenders.add(rel + " → " + prefix + "…");
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("화면 코드가 특정인의 인증서 일련번호를 들고 있다. 그 인증서의 "
                    + "주인이 직원이면 로그인은 되고 고객 API 는 403 이 되어 "
                    + "'로그인은 됐는데 마이페이지만 안 뜨는' 상태가 되고, 고객이면 "
                    + "방문자 누구나 PIN 만 맞히면 남의 계정으로 들어온다. "
                    + "lib/issued-cert.ts 의 readIssuedCert 로 이 브라우저에서 "
                    + "발급받은 인증서를 읽을 것")
                .isEmpty();
    }
}
