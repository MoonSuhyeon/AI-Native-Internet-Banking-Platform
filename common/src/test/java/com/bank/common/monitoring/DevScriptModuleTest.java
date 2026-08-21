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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발 스크립트가 실제로 있는 Gradle 모듈만 부르는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 서비스를 병합한 뒤 {@code scripts/dev-quick.ps1} 과
 * {@code dev-full.ps1} 이 없어진 모듈을 계속 부르고 있었다.
 *
 * <pre>
 * gradlew.bat :services:deposit-service:bootRun   ← 모듈 없음
 * gradlew.bat :services:payment-service:bootRun   ← 모듈 없음
 * gradlew.bat :services:master-service:bootRun    ← 존재한 적 없음
 * </pre>
 *
 * <p>이 상태는 <b>레포를 처음 받은 사람에게만 드러난다.</b> 이미 환경이 있는 사람은
 * 스크립트를 안 쓰고 IDE 로 띄우기 때문이다. 그래서 오래 남았다.
 *
 * <p>같은 계열로 문서(`ApiSpecFreshnessTest`)와 게이트웨이 라우트
 * (`GatewayRouteCoverageTest`)를 이미 못 박아 두었다. 실행 스크립트가 셋째 자리다 —
 * <b>"선언한 것과 실제가 다른데 아무도 모른다"</b> 는 같은 문제다.
 *
 * <p><b>보지 않는 것.</b> 스크립트가 실제로 도는지는 보지 않는다(포트 충돌·환경변수·
 * 기동 순서). 여기서 막는 것은 "부를 대상이 아예 없다" 한 종류뿐이다.
 */
class DevScriptModuleTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path SCRIPTS = ROOT.resolve("scripts");
    private static final Path SETTINGS = ROOT.resolve("settings.gradle");

    /** {@code :services:core-banking:bootRun} 같은 호출에서 모듈 경로를 뽑는다. */
    private static final Pattern GRADLE_TASK =
            Pattern.compile(":((?:services|agents|common)(?::[A-Za-z0-9._-]+)*):[A-Za-z0-9]+");

    /** {@code include 'services:core-banking'} */
    private static final Pattern INCLUDE =
            Pattern.compile("include\\s+'([^']+)'");

    @Test
    @DisplayName("개발 스크립트가 없는 Gradle 모듈을 부르지 않는다")
    void devScriptsReferenceRealModules() throws IOException {
        Set<String> declared = declaredModules();
        assertThat(declared)
                .as("settings.gradle 에서 모듈을 하나도 읽지 못했다 — 검사가 의미 없어진다")
                .isNotEmpty();

        Set<String> ghosts = new TreeSet<>();
        try (Stream<Path> files = Files.list(SCRIPTS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".ps1")).toList()) {
                String text = Files.readString(f);
                Matcher m = GRADLE_TASK.matcher(text);
                while (m.find()) {
                    String module = m.group(1);
                    if (!declared.contains(module)) {
                        ghosts.add(f.getFileName() + " → :" + module);
                    }
                }
            }
        }

        assertThat(ghosts)
                .as("스크립트가 부르는 Gradle 모듈이 settings.gradle 에 없다. "
                    + "레포를 처음 받은 사람이 실행하면 'project not found' 로 죽는다. "
                    + "이미 환경이 있는 사람은 스크립트를 안 써서 안 드러난다")
                .isEmpty();
    }

    private static Set<String> declaredModules() throws IOException {
        Set<String> modules = new LinkedHashSet<>();
        Matcher m = INCLUDE.matcher(Files.readString(SETTINGS));
        while (m.find()) {
            modules.add(m.group(1));
        }
        return modules;
    }
}
