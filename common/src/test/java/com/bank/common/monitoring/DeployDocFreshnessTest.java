package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포 문서가 실제로 있는 워크플로를 설명하는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code docs/DEPLOY.md} 의 워크플로 표가 오래 낡아 있었다.
 *
 * <ul>
 *   <li>{@code deploy-deposit-service.yml}·{@code deploy-payment-service.yml} 을
 *       적어 뒀지만 둘은 병합돼 {@code deploy-core-banking.yml} 하나가 됐다</li>
 *   <li>"loan-service 전용 배포 워크플로가 없다" 고 경고까지 달아 뒀지만
 *       {@code deploy-loan-service.yml} 은 <b>실제로 있었다</b></li>
 * </ul>
 *
 * <p>배포 문서는 <b>급할 때 읽는 문서</b>다. 서버가 안 뜨는 상황에서 없는 워크플로를
 * 찾거나, 있는 것을 없다고 믿고 새로 만들게 된다. 다른 문서보다 낡았을 때의 값이 크다.
 *
 * <p><b>양방향으로 본다.</b> API 명세와 달리 여기는 목록이 짧고 완결적이라 거짓 경보
 * 걱정이 없다 — 문서에만 있는 것도, 파일에만 있는 것도 둘 다 잘못이다.
 */
class DeployDocFreshnessTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DOC = ROOT.resolve("docs").resolve("DEPLOY.md");
    private static final Path WORKFLOWS = ROOT.resolve(".github").resolve("workflows");

    @Test
    @DisplayName("배포 문서의 워크플로 목록이 실제 파일과 같다")
    void deployDocMatchesWorkflowFiles() throws IOException {
        Set<String> onDisk = deployWorkflowFiles();
        assertThat(onDisk)
                .as("deploy-*.yml 을 하나도 못 찾았다 — 검사가 의미 없어진다")
                .isNotEmpty();

        String doc = Files.readString(DOC);

        Set<String> undocumented = new TreeSet<>();
        for (String file : onDisk) {
            if (!doc.contains(file)) {
                undocumented.add(file);
            }
        }

        Set<String> ghosts = new TreeSet<>();
        for (String mentioned : mentionedWorkflows(doc)) {
            if (!onDisk.contains(mentioned)) {
                ghosts.add(mentioned);
            }
        }

        assertThat(undocumented)
                .as("배포 워크플로가 있는데 DEPLOY.md 가 설명하지 않는다. "
                    + "급할 때 읽는 문서라, 빠진 것은 없는 것과 같다")
                .isEmpty();

        assertThat(ghosts)
                .as("DEPLOY.md 가 설명하는데 실제로 없는 워크플로다. "
                    + "서버가 안 뜨는 상황에서 없는 파일을 찾게 만든다")
                .isEmpty();
    }

    private static Set<String> deployWorkflowFiles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        if (!Files.isDirectory(WORKFLOWS)) {
            return names;
        }
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith("deploy-") && name.endsWith(".yml")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 워크플로 <b>표</b>에 적힌 이름들.
     *
     * <p>본문 전체를 훑지 않는다. 처음에는 그렇게 했다가 셋을 잘못 잡았다.
     *
     * <ul>
     *   <li>{@code reusable-deploy-ssh.yml} — 이름 가운데의 {@code deploy-} 를
     *       파일명 시작으로 읽었다. 이건 재사용 워크플로이지 서비스 배포가 아니다</li>
     *   <li>{@code deploy-*.yml} — 산문 속 glob 을 파일명으로 읽었다</li>
     *   <li>"예전에는 이러이러했다" 는 <b>설명</b>을 현재 목록으로 읽었다.
     *       없앤 것을 설명하는 문장은 지워야 할 대상이 아니다</li>
     * </ul>
     *
     * <p>표의 첫 칸만 보면 셋 다 자연히 걸러진다. 목록이 사는 곳이 거기이기도 하다.
     */
    private static Set<String> mentionedWorkflows(String doc) {
        Set<String> names = new TreeSet<>();

        for (String line : doc.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("| `deploy-")) {
                continue;
            }
            int start = trimmed.indexOf('`') + 1;
            int end = trimmed.indexOf('`', start);
            if (end > start) {
                names.add(trimmed.substring(start, end));
            }
        }
        return names;
    }
}
