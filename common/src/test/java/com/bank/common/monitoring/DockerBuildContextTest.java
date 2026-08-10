package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dockerfile 이 <b>자기가 빌드하는 모듈의 의존 모듈까지</b> 복사하는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> Gradle 로는 멀티모듈이 알아서 엮이지만, Docker 빌드는 복사한
 * 파일만 본다. 그래서 {@code build.gradle} 에 모듈 의존을 하나 추가하고 Dockerfile 을
 * 안 고치면 <b>이미지 빌드만</b> 깨진다.
 *
 * <p>그 실패가 늦게 드러난다. 로컬 테스트도, CI 의 gradle 빌드도 전부 통과하기
 * 때문이다. 실제로 이 레포에서 auto-loan-review 와 review-ai-gateway 가 그 상태였다 —
 * harness-core 의존이 추가됐는데 Dockerfile 에는 없어서, 이미지를 만들려는 순간
 * {@code package com.bank.harness.validation does not exist} 로 죽었다.
 * 배포를 시도하기 전까지 아무도 몰랐다.
 *
 * <p>전이 의존까지는 보지 않는다. 직접 의존만 확인해도 이 계열의 사고는 대부분 걸린다.
 */
class DockerBuildContextTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** {@code implementation project(':agents:harness-core')} 에서 경로를 뽑는다. */
    private static final Pattern PROJECT_DEP =
            Pattern.compile("project\\('(:[^']+)'\\)");

    @Test
    @DisplayName("Dockerfile 이 의존 모듈의 소스를 복사한다 — 빠지면 이미지 빌드만 깨진다")
    void everyDockerfileCopiesItsModuleDependencies() throws IOException {
        List<String> missing = new ArrayList<>();

        for (Path dockerfile : dockerfiles()) {
            Path moduleDir = dockerfile.getParent();
            Path buildGradle = moduleDir.resolve("build.gradle");
            if (!Files.exists(buildGradle)) {
                continue;   // 모듈이 아닌 Dockerfile (인프라용 등)
            }

            String docker = Files.readString(dockerfile);
            for (String dep : projectDependencies(buildGradle)) {
                if (!docker.contains(dep + "/src")) {
                    missing.add(REPO_ROOT.relativize(dockerfile) + " → " + dep
                            + " (COPY 가 없다)");
                }
            }
        }

        assertThat(missing)
                .as("Dockerfile 이 의존 모듈을 복사하지 않는다. Gradle 빌드는 통과하므로 "
                    + "이미지를 만들어 보기 전까지 드러나지 않는다.")
                .isEmpty();
    }

    /** {@code :agents:harness-core} → {@code agents/harness-core} */
    private static Set<String> projectDependencies(Path buildGradle) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PROJECT_DEP.matcher(Files.readString(buildGradle));
        while (m.find()) {
            out.add(m.group(1).substring(1).replace(':', '/'));
        }
        return out;
    }

    private static List<Path> dockerfiles() throws IOException {
        try (Stream<Path> paths = Files.walk(REPO_ROOT, 3)) {
            return paths
                    .filter(p -> p.getFileName().toString().equals("Dockerfile"))
                    .filter(p -> !p.toString().contains("build" + java.io.File.separator))
                    .toList();
        }
    }
}
