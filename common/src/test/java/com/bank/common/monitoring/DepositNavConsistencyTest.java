package com.bank.common.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상단 금융상품 드롭다운과 예금 사이드바가 같은 화면을 가리키는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 둘은 손으로 관리되는 <b>별개의 배열</b>이다
 * ({@code Header.tsx} 의 {@code GNB_MENUS}, {@code DepositSidebar.tsx} 의 {@code NAV}).
 * 실제로 어긋나 있었다 — 드롭다운은 '예금상품 / 결과·내역 조회' 로 나뉘는데
 * 사이드바는 '예금 상품·가입 / 예금 조회·해지' 로 갈라, 같은 화면이 서로 다른
 * 갈래에 놓였다.
 *
 * <p>화면은 멀쩡히 그려진다. 드롭다운으로 들어가면 <b>사이드바가 "지금 어디인지" 를
 * 다르게 말할 뿐</b>이고, 눈으로 두 목록을 대조해야만 드러난다.
 *
 * <p><b>대출과 달리 정확히 같은 URL 을 요구한다.</b> 예금은 화면이 넷뿐이라 드롭다운과
 * 사이드바가 같은 집합을 가리키는 것이 맞다. 한쪽에만 있는 것이 생기면 그 자체가 어긋남이다.
 */
class DepositNavConsistencyTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();
    private static final Path HEADER = WEB.resolve("components/layout/Header.tsx");
    private static final Path SIDEBAR = WEB.resolve("components/products/DepositSidebar.tsx");

    private static final Pattern HREF = Pattern.compile("href:\\s*'(/products/deposit/[^']*)'");

    @Test
    @DisplayName("상단 금융상품 메뉴와 예금 사이드바가 같은 화면을 가리킨다")
    void dropdownAndSidebarPointAtTheSameScreens() throws IOException {
        Set<String> gnb = depositHrefsOf(Files.readString(HEADER));
        Set<String> sidebar = depositHrefsOf(Files.readString(SIDEBAR));

        assertThat(gnb)
                .as("상단 메뉴에서 예금 경로를 하나도 못 읽었다 — 검사가 의미 없어진다")
                .isNotEmpty();
        assertThat(sidebar)
                .as("사이드바에서 예금 경로를 하나도 못 읽었다 — 검사가 의미 없어진다")
                .isNotEmpty();

        Set<String> onlyInGnb = new TreeSet<>(gnb);
        onlyInGnb.removeAll(sidebar);
        Set<String> onlyInSidebar = new TreeSet<>(sidebar);
        onlyInSidebar.removeAll(gnb);

        assertThat(onlyInGnb)
                .as("드롭다운에 있는데 사이드바에 없는 화면이다. 그 화면에 들어가면 "
                    + "사이드바가 '지금 어디인지' 를 못 보여 준다")
                .isEmpty();
        assertThat(onlyInSidebar)
                .as("사이드바에 있는데 드롭다운에 없는 화면이다. 상단 메뉴로는 갈 방법이 없다")
                .isEmpty();
    }

    private static Set<String> depositHrefsOf(String source) {
        Set<String> found = new TreeSet<>();
        Matcher m = HREF.matcher(source);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
