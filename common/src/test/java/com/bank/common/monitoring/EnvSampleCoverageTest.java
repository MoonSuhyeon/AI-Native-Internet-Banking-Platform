package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * compose 가 요구하는 환경변수가 {@code .env.sample} 에 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code CONSULTATION_GATEWAY_SHARED_SECRET} 이
 * {@code .env.sample} 에 없었다. 게이트웨이는 기본값 {@code not-configured} 를
 * 붙여 보내고 상담 서비스는 자기 쪽이 비어 있어 <b>어떤 신원도 믿지 않는다</b>.
 * 결과는 챗봇 전부 401.
 *
 * <p>이 실패는 <b>fail-closed 라서 조용하다.</b> 뚫리지는 않지만, 값이 없다는 것도
 * 알기 어렵다 — 로그에는 그냥 401 만 남고 "무엇을 설정해야 하는지" 는 어디에도
 * 안 적혀 있었다. 새로 받은 사람은 챗봇을 켤 방법이 없다.
 *
 * <p><b>기본값이 있으면 통과시킨다.</b> {@code ${VAR:-default}} 는 설정하지 않아도
 * 도는 값이라 sample 에 없어도 된다. 문제는 <b>기본값이 없거나, 기본값이
 * 있어도 그 값으로는 기능이 죽는</b> 경우다. 뒤쪽은 기계가 알 수 없으므로
 * 아래 목록으로 못 박는다.
 */
class EnvSampleCoverageTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path COMPOSE = ROOT.resolve("docker-compose.yml");
    private static final Path SAMPLE = ROOT.resolve(".env.sample");

    /** {@code ${NAME}} · {@code ${NAME:-default}} */
    private static final Pattern REF = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:-[^}]*)?\\}");
    private static final Pattern DECLARED = Pattern.compile("(?m)^([A-Z][A-Z0-9_]*)=");

    /**
     * 기본값이 있어도 그 값으로는 기능이 죽는 것들.
     *
     * <p>{@code CONSULTATION_GATEWAY_SHARED_SECRET} 의 기본값은
     * {@code not-configured} 다. 게이트웨이는 뜨지만 상담 인증이 전부 막힌다 —
     * 빈 문자열을 쓰면 게이트웨이가 아예 기동하지 않아서 넣어 둔 값이고,
     * "뜨기는 하지만 안 되는" 상태를 만든다.
     */
    private static final Set<String> MUST_BE_IN_SAMPLE = Set.of(
            "CONSULTATION_GATEWAY_SHARED_SECRET");

    @Test
    @DisplayName("기본값 없는 환경변수는 .env.sample 에 있다")
    void everyRequiredVarIsSampled() throws IOException {
        Set<String> declared = declaredNames();
        assertThat(declared)
                .as(".env.sample 에서 변수를 하나도 읽지 못했다 — 검사가 의미 없어진다")
                .isNotEmpty();

        String compose = Files.readString(COMPOSE);
        Set<String> missing = new TreeSet<>();

        Matcher m = REF.matcher(compose);
        while (m.find()) {
            String name = m.group(1);
            boolean hasDefault = m.group(2) != null;
            if (declared.contains(name)) {
                continue;
            }
            if (!hasDefault || MUST_BE_IN_SAMPLE.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing)
                .as("compose 가 쓰는데 .env.sample 에 없는 변수다. 새로 받은 사람은 "
                    + "무엇을 설정해야 하는지 알 수 없고, fail-closed 인 것은 조용히 "
                    + "전부 거절만 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("기능이 죽는 기본값을 가진 변수는 sample 에 반드시 있다")
    void secretsWithBrokenDefaultsAreSampled() throws IOException {
        Set<String> declared = declaredNames();

        Set<String> missing = new TreeSet<>();
        for (String name : MUST_BE_IN_SAMPLE) {
            if (!declared.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing)
                .as("기본값이 있어 컨테이너는 뜨지만 그 값으로는 기능이 죽는 변수다. "
                    + "sample 에 없으면 '떴는데 안 되는' 상태가 된다")
                .isEmpty();
    }

    private static Set<String> declaredNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = DECLARED.matcher(Files.readString(SAMPLE));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
