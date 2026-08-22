package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고객 안내가 탐지기에서 화면까지 필드 이름 그대로 건너가는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 이 값은 서비스 셋과 프론트를 지나며 <b>같은 모양의 타입이
 * 세 번 선언된다.</b>
 *
 * <ul>
 *   <li>탐지기 {@code ConsumerGuidance} — 만드는 곳</li>
 *   <li>결제계 {@code RiskGuidance} — 나르는 곳</li>
 *   <li>프론트 {@code RiskGuidance} — 그리는 곳</li>
 * </ul>
 *
 * <p>일부러 나눴다. 공유하면 결제계가 탐지기 모듈에 컴파일 의존하게 되고, 지금 둘은
 * HTTP 로만 이어져 있다. 대신 <b>이름이 어긋나면 값이 조용히 빈다</b> —
 * {@code ignoreUnknown} 이라 역직렬화가 실패하지도 않는다. 화면에는 안내가 없는 채로
 * 뜨고, 그건 이 작업 이전 상태와 똑같다. 고쳐 놓고 되돌아가는 셈이다.
 *
 * <p>Jackson 이 카멜케이스로 맞춰 주므로 자바 둘은 이름이 같아야 하고, 프론트는
 * 그 JSON 을 그대로 읽으므로 역시 같아야 한다.
 *
 * <p><b>보지 않는 것.</b> 문구가 좋은지는 보지 않는다. 여기서 막는 것은 <b>배선이
 * 끊겨 안내가 조용히 사라지는</b> 한 종류다.
 */
class GuidanceContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final Path DETECTOR = ROOT.resolve(
            "services/fds-detector/src/main/java/com/bank/fds/guidance/ConsumerGuidance.java");
    private static final Path PAYMENT = ROOT.resolve(
            "services/core-banking/src/main/java/com/bank/deposit/security/RiskGuidance.java");
    private static final Path WEB = ROOT.resolve("web/lib/risk-guidance.ts");

    /** {@code record X(...)} 의 괄호 안. */
    private static final Pattern RECORD_BODY = Pattern.compile(
            "record\\s+\\w+\\s*\\(([^)]*)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("탐지기·결제계·프론트가 같은 이름의 안내 필드를 쓴다")
    void guidanceFieldsMatchAcrossServices() throws IOException {
        List<String> detector = recordComponents(DETECTOR);
        List<String> payment = recordComponents(PAYMENT);
        List<String> web = tsFields(WEB);

        assertThat(detector)
                .as("탐지기 안내 타입에서 필드를 못 읽었다 — 검사가 아무것도 지키지 않는다")
                .isNotEmpty();

        assertThat(payment)
                .as("결제계가 나르는 필드가 탐지기가 만드는 필드와 다르다. Jackson 은 "
                    + "모르는 필드를 조용히 버리므로 역직렬화는 성공하고 값만 빈다 — "
                    + "화면에 안내가 없는 채로 뜨고, 그건 이 작업 이전과 똑같은 상태다")
                .containsExactlyElementsOf(detector);

        assertThat(web)
                .as("화면이 읽는 필드가 서버가 보내는 필드와 다르다. 위와 같은 이유로 "
                    + "오류 없이 빈 안내가 그려진다")
                .containsExactlyElementsOf(detector);
    }

    /** 레코드 선언에서 컴포넌트 이름만. */
    private static List<String> recordComponents(Path file) throws IOException {
        String source = TsSource.withoutComments(Files.readString(file));
        Matcher m = RECORD_BODY.matcher(source);
        List<String> names = new ArrayList<>();
        if (!m.find()) {
            return names;
        }
        for (String part : m.group(1).split(",")) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length >= 2) {
                names.add(tokens[tokens.length - 1]);
            }
        }
        return names;
    }

    /**
     * {@code type RiskGuidance = { ... }} 의 필드 이름만.
     *
     * <p>주석을 먼저 걷어낸다. 이 타입의 주석은 각 필드가 무엇인지 설명하느라
     * 다른 필드 이름을 인용한다 — 그대로 훑으면 없는 필드를 읽는다.
     */
    private static List<String> tsFields(Path file) throws IOException {
        String source = TsSource.withoutComments(Files.readString(file));
        int start = source.indexOf("type RiskGuidance = {");
        if (start < 0) {
            return List.of();
        }
        int end = source.indexOf('}', start);
        List<String> names = new ArrayList<>();
        for (String line : source.substring(start, end).split("\n")) {
            Matcher m = Pattern.compile("^\\s*(\\w+)\\??\\s*:").matcher(line);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
