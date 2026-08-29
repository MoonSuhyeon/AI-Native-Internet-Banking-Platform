package com.bank.payment.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 API 가 인가 없이 늘어나는 것을 막는다.
 *
 * <p><b>왜 이 테스트가 있는가.</b> 이체 승인 게이트를 켤 때 적용 대상 경로를 사람이
 * 주석에 적었다 — "이 게이트를 지나는 경로는 셋이고 전부 토큰을 보낸다". 그런데
 * 실제로는 일곱이었고 여신 넷이 빠져 있었다. 그 결과 여신 자금이동 전체가 401 로
 * 막혔고, 아무도 몇 달 동안 몰랐다.
 *
 * <p>같은 종류의 누락을 사람의 성실함으로 막을 수 없다. <b>경로 목록을 주석이 아니라
 * 코드에서 도출한다.</b>
 *
 * <p><b>무엇을 보는가.</b> {@code /v1/internal/**} 을 여는 컨트롤러가 인가를 지나는지
 * 본다. 서비스 호출은 {@code ServiceAuthorizationService} 로, 직원 운영 작업은 직원
 * 신원으로 인가한다(근거: {@code docs/decisions/transaction-initiator-auth-model.md} §7).
 * 둘 중 아무것도 하지 않는 컨트롤러가 새로 생기면 여기서 잡힌다.
 *
 * <p><b>무엇을 보지 않는가.</b> 인가가 <i>올바른지</i>는 보지 않는다. 그건 각
 * 컨트롤러의 테스트가 볼 일이고, 기계가 판정하려 들면 거짓 경보가 난다.
 */
class InternalEndpointCoverageTest {

    private static final Path CONTROLLER_ROOT =
            Path.of("src", "main", "java", "com", "bank").toAbsolutePath().normalize();

    private static final Pattern INTERNAL_MAPPING =
            Pattern.compile("@RequestMapping\\(\"(/v1/internal/[^\"]*)\"\\)");

    /** 서비스 신원으로 인가한다. */
    private static final String SERVICE_AUTHORIZATION = "ServiceAuthorizationService";

    /**
     * 직원 신원으로 인가한다.
     *
     * <p>A2 가 세운 열람 관문이다. 행위자를 세우고 자원 접근을 판정한다 —
     * 서비스 자격증명과는 다른 주체 모델이다.
     */
    private static final String EMPLOYEE_AUTHORIZATION = "ResourceAccessGuard";

    /**
     * 아직 인가가 없는 것.
     *
     * <p>비워 두면 새 컨트롤러를 막을 수 없고, 그렇다고 여기 적어 두면 영원히
     * 남는다. 그래서 <b>왜 아직인지와 어디서 다루는지를 함께</b> 적는다.
     * 전환이 끝나면 이 목록은 비어야 한다.
     */
    private static final Map<String, String> PENDING = new TreeMap<>(Map.of(
            "InternalReconciliationController.java",
            "사람이 부르는 운영 작업. 직원 인증 경로로 다룬다 — ADR §7"
    ));

    // 파일 단위로 센다. 같은 경로를 여는 컨트롤러가 둘일 수 있기 때문이다 —
    // /v1/internal/payments 는 조회(InternalPaymentController)와
    // 생성(SystemPaymentController)이 나눠 쓰고, 인가 상태가 서로 다르다.
    // 경로로 묶으면 인가 있는 쪽이 없는 쪽을 가린다.

    private record InternalController(String path, String file, boolean authorized) {
    }

    private static List<InternalController> scan() throws IOException {
        try (Stream<Path> files = Files.walk(CONTROLLER_ROOT)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .map(InternalEndpointCoverageTest::read)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    private static InternalController read(Path file) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("컨트롤러를 읽지 못했다: " + file, e);
        }
        Matcher m = INTERNAL_MAPPING.matcher(source);
        if (!m.find()) {
            return null;
        }
        boolean authorized = source.contains(SERVICE_AUTHORIZATION)
                || source.contains(EMPLOYEE_AUTHORIZATION);
        return new InternalController(m.group(1), file.getFileName().toString(), authorized);
    }

    @Test
    @DisplayName("내부 API 는 인가를 지나거나, 아직인 이유가 적혀 있다")
    void everyInternalEndpointIsAuthorizedOrKnown() throws IOException {
        var unguarded = new TreeSet<String>();
        for (InternalController c : scan()) {
            if (c.authorized()) {
                continue;
            }
            // 경로가 PENDING 의 어느 항목에 속하는지 본다. 하위 경로도 같은 취급이다.
            boolean known = PENDING.containsKey(c.file());
            if (!known) {
                unguarded.add(c.path() + " (" + c.file() + ")");
            }
        }

        assertThat(unguarded)
                .as("인가를 지나지 않는 내부 API 다. 서비스 호출이면 "
                    + "ServiceAuthorizationService 로, 직원 작업이면 직원 신원으로 인가해야 한다. "
                    + "아직 붙일 수 없다면 PENDING 에 이유와 함께 적는다 — "
                    + "근거는 docs/decisions/transaction-initiator-auth-model.md §7")
                .isEmpty();
    }

    @Test
    @DisplayName("PENDING 에 적힌 컨트롤러는 실제로 존재한다")
    void pendingEntriesStillExist() throws IOException {
        List<InternalController> found = scan();
        var stale = new TreeSet<String>();
        for (String pending : PENDING.keySet()) {
            boolean exists = found.stream().anyMatch(c -> c.file().equals(pending));
            if (!exists) {
                stale.add(pending);
            }
        }

        assertThat(stale)
                .as("없는 컨트롤러가 PENDING 에 남아 있다. 전환이 끝났거나 경로가 사라진 것이다 — "
                    + "지우지 않으면 목록이 현실과 어긋나 아무도 믿지 않게 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("이미 인가를 지나는 컨트롤러는 PENDING 에 남기지 않는다")
    void authorizedEndpointsAreNotPending() throws IOException {
        var lingering = new TreeSet<String>();
        for (InternalController c : scan()) {
            if (c.authorized() && PENDING.containsKey(c.file())) {
                lingering.add(c.file());
            }
        }

        assertThat(lingering)
                .as("인가가 붙었는데 PENDING 에 남아 있다. 전환이 끝나면 지워야 "
                    + "임시 허용이 정책으로 굳지 않는다")
                .isEmpty();
    }
}
