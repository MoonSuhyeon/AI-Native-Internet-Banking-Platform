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
 * 상단 대출 메뉴와 대출 사이드바가 같은 곳을 가리키는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 둘은 손으로 관리되는 <b>별개의 배열</b>이다
 * ({@code Header.tsx} 의 {@code GNB_MENUS}, {@code LoanSidebar.tsx} 의 {@code NAV}).
 * 실제로 어긋나 있었다 — 드롭다운에는 "대출 가이드" 와 "여신심사 자료제출" 이
 * 있는데 사이드바에는 없었다.
 *
 * <p>화면은 멀쩡히 그려진다. 드롭다운으로 그 화면에 들어가면 <b>사이드바가 "지금
 * 어디인지" 를 못 보여 줄 뿐</b>이고, 그 자리에서 형제 메뉴로 넘어갈 방법도 없다.
 * 눈으로 두 목록을 대조해야만 드러난다.
 *
 * <p><b>무엇을 보는가.</b> 상단 메뉴의 대출 항목이 사이드바에도 있는지 <b>한 방향만</b>
 * 본다. 반대 방향(사이드바에만 있는 것)은 정상이다 — 사이드바가 더 자세한 것은
 * 의도된 구조이고, 드롭다운에 100개를 넣을 수는 없다.
 *
 * <p>경로 접두사로만 비교한다. 드롭다운은 절의 대표 화면 하나를 가리키고
 * ({@code /products/loan/guide/rate}) 사이드바는 그 절의 항목을 모두 펼치므로,
 * 정확히 같은 URL 을 요구하면 거짓 경보가 난다.
 */
class LoanNavConsistencyTest {

    private static final Path WEB = Path.of("..", "web").toAbsolutePath().normalize();
    private static final Path HEADER = WEB.resolve("components/layout/Header.tsx");
    private static final Path SIDEBAR = WEB.resolve("components/inquiry/LoanSidebar.tsx");

    private static final Pattern HREF = Pattern.compile("href:\\s*'(/products/loan/[^']*)'");

    @Test
    @DisplayName("상단 대출 메뉴의 항목이 사이드바에도 있다")
    void everyGnbLoanEntryIsInTheSidebar() throws IOException {
        Set<String> gnb = loanHrefsOf(Files.readString(HEADER));
        Set<String> sidebar = loanHrefsOf(Files.readString(SIDEBAR));

        assertThat(gnb)
                .as("상단 메뉴에서 대출 경로를 하나도 못 읽었다 — 검사가 의미 없어진다")
                .isNotEmpty();
        assertThat(sidebar)
                .as("사이드바에서 대출 경로를 하나도 못 읽었다 — 검사가 의미 없어진다")
                .isNotEmpty();

        Set<String> missing = new TreeSet<>();
        for (String href : gnb) {
            String section = sectionOf(href);
            boolean covered = sidebar.stream().anyMatch(s -> sectionOf(s).equals(section));
            if (!covered) {
                missing.add(href);
            }
        }

        assertThat(missing)
                .as("상단 대출 메뉴에 있는데 사이드바에 없는 절이다. 드롭다운으로 들어가면 "
                    + "사이드바가 '지금 어디인지' 를 못 보여 주고, 형제 메뉴로 넘어갈 "
                    + "방법도 없다. 두 목록이 손으로 관리되는 별개의 배열이라 어긋난다")
                .isEmpty();
    }

    /** {@code /products/loan/guide/rate} → {@code /products/loan/guide} */
    private static String sectionOf(String href) {
        String[] parts = href.split("/");
        // ["", "products", "loan", "<section>", ...]
        return parts.length >= 4 ? "/products/loan/" + parts[3] : href;
    }

    private static Set<String> loanHrefsOf(String source) {
        Set<String> hrefs = new LinkedHashSet<>();
        Matcher m = HREF.matcher(source);
        while (m.find()) {
            hrefs.add(m.group(1));
        }
        return hrefs;
    }
}
