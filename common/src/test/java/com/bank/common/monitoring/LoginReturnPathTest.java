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
 * 로그인을 거치면서 가려던 곳을 잃지 않는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 홈에서 "AI 대출 심사" 를 누르면 이렇게 됐다.
 *
 * <ol>
 *   <li>{@code /loans/apply} 로 간다</li>
 *   <li>{@code AuthGuard} 가 {@code router.replace('/login')} 으로 되돌린다 —
 *       <b>어디로 가려던 참이었는지는 아무 데도 남지 않는다</b></li>
 *   <li>로그인에 성공한다</li>
 *   <li>로그인 화면이 {@code window.location.href = '/'} 로 <b>홈</b>을 띄운다</li>
 * </ol>
 *
 * <p>각 단계는 전부 성공했는데, 사용자에게는 <b>"버튼을 눌러도 안 넘어간다"</b> 로
 * 보인다. 누른 것과 나온 것이 이어지지 않기 때문이다. 오류도 아니고 빈 화면도
 * 아니라서 죽은 버튼보다 찾기 어렵다.
 *
 * <p><b>무엇을 보는가.</b> 두 가지다.
 *
 * <ul>
 *   <li>로그인으로 <b>보내는</b> 쪽 — 목적지 없이 {@code '/login'} 으로만 보내지 않는다</li>
 *   <li>로그인에서 <b>돌아오는</b> 쪽 — 로그인 화면이 갈 곳을 {@code '/'} 로 박지 않는다</li>
 * </ul>
 *
 * <p>한쪽만 고치면 소용이 없다. 목적지를 실어 보내도 로그인 화면이 무시하면 그대로고,
 * 로그인 화면이 읽을 줄 알아도 아무도 실어 보내지 않으면 그대로다.
 *
 * <p><b>보지 않는 것.</b> 사용자가 실제로 그 화면을 볼 권한이 있는지는 보지 않는다.
 * 그건 게이트웨이의 일이다. 여기서 막는 것은 <b>이동 중에 목적지를 잃는</b> 한 종류다.
 */
class LoginReturnPathTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();

    /** 목적지를 싣지 않고 로그인으로 보내는 표현. */
    private static final List<String> BARE_REDIRECTS = List.of(
            "router.replace('/login')",
            "router.push('/login')",
            "window.location.href = '/login'",
            "location.href = '/login'");

    /** 로그인 성공 뒤 갈 곳을 박아 둔 표현. */
    private static final String HARDCODED_HOME = "window.location.href = '/'";

    /** 로그인 화면들. 여기서 갈 곳을 정한다. */
    private static final List<String> LOGIN_SCREENS = List.of(
            "app/(personal)/login/page.tsx",
            "app/(personal)/login/pin/page.tsx");

    @Test
    @DisplayName("로그인으로 보낼 때 가려던 곳을 함께 싣는다")
    void guardsCarryTheDestination() throws IOException {
        Set<String> offenders = new TreeSet<>();

        for (Path f : frontendSources()) {
            String rel = WEB.relativize(f).toString().replace('\\', '/');
            // 주석은 뺀다 — 이 사고를 설명하는 주석들이 금지 표현을
            // 그대로 인용한다(TsSource 주석 참고).
            String text = TsSource.withoutComments(Files.readString(f));
            for (String bare : BARE_REDIRECTS) {
                if (text.contains(bare)) {
                    offenders.add(rel + " → " + bare);
                }
            }
        }

        assertThat(offenders)
                .as("목적지 없이 로그인으로 보낸다. 로그인에 성공해도 사용자가 누른 적 "
                    + "없는 홈이 뜨고, 각 단계가 다 성공했는데도 '버튼이 안 먹는다' 로 "
                    + "보인다. lib/return-url.ts 의 loginUrlFor 를 쓸 것")
                .isEmpty();
    }

    @Test
    @DisplayName("로그인 성공 뒤 갈 곳을 홈으로 박아 두지 않는다")
    void loginScreensHonorTheDestination() throws IOException {
        Set<String> offenders = new TreeSet<>();

        for (String rel : LOGIN_SCREENS) {
            Path f = WEB.resolve(rel);
            assertThat(f)
                    .as("로그인 화면 경로가 바뀌었다 — 이 검사가 아무것도 지키지 않는 "
                        + "상태다. 목록을 고칠 것: " + rel)
                    .exists();
            if (TsSource.withoutComments(Files.readString(f)).contains(HARDCODED_HOME)) {
                offenders.add(rel);
            }
        }

        assertThat(offenders)
                .as("로그인 성공 뒤 무조건 홈으로 보낸다. 가드가 목적지를 실어 보내도 "
                    + "여기서 버리면 소용이 없다. lib/return-url.ts 의 postLoginTarget "
                    + "을 쓸 것")
                .isEmpty();
    }

    private static List<Path> frontendSources() throws IOException {
        List<Path> found = new java.util.ArrayList<>();
        for (String dir : List.of("app", "components", "lib")) {
            Path root = WEB.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> {
                            String n = f.getFileName().toString();
                            return n.endsWith(".ts") || n.endsWith(".tsx");
                        })
                        .forEach(found::add);
            }
        }
        return found;
    }
}
