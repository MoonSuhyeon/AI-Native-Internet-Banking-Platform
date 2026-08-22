package com.bank.common.monitoring;

/**
 * TypeScript 소스를 <b>코드</b>와 <b>문자열</b>로 갈라 보는 도우미.
 *
 * <p><b>왜 필요한가.</b> 프론트 코드에 "이런 표현이 있으면 안 된다" 는 검사를
 * 여럿 두고 있는데, 전문 검색으로 하면 <b>주석이 걸린다.</b> 그리고 이 레포의
 * 주석은 대부분 "예전에 이렇게 해서 이런 사고가 났다" 는 기록이라, 금지하려는
 * 표현을 그대로 인용한다. 지워야 할 대상이 아니라 남겨야 할 기록이다.
 *
 * <p>거꾸로 어떤 검사는 <b>문자열 안</b>만 봐야 한다. 그때도 같은 이유로 주석은
 * 빼야 한다.
 *
 * <p><b>왜 정규식이 아닌가.</b> 주석을 정규식으로 지우려다 {@code http://} 의
 * 슬래시 둘을 줄 주석으로 읽어 그 줄의 뒤쪽을 통째로 날린 적이 있다. 검사가
 * 조용히 안 도는 쪽이라 통과했다 — 틀린 답보다 나쁘다. 문자열과 주석은 서로
 * 중첩되므로 한 번 훑으면서 상태를 들고 가야 한다.
 *
 * <p><b>보지 않는 것.</b> 파서가 아니다. 정규식 리터럴({@code /.../})은 나눗셈과
 * 구별하지 않고, 템플릿 문자열 안의 {@code ${...}} 도 문자열로 친다. 지금 쓰는
 * 검사들에는 둘 다 영향이 없다.
 */
final class TsSource {

    private TsSource() {
    }

    /** 주석을 뺀 나머지. 문자열 리터럴은 그대로 둔다. */
    static String withoutComments(String source) {
        return scan(source, true);
    }

    /** 문자열 리터럴 안의 내용만. 주석은 빠진다. */
    static String stringLiterals(String source) {
        return scan(source, false);
    }

    private static String scan(String source, boolean keepCode) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = source.length();

        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                if (keepCode) out.append(quote);
                i++;
                while (i < n && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\') {
                        if (keepCode) out.append(source, i, Math.min(i + 2, n));
                        i += 2;
                        continue;
                    }
                    out.append(source.charAt(i));
                    i++;
                }
                out.append(keepCode ? quote : '\n');
                i++;
                continue;
            }
            if (keepCode) out.append(c);
            i++;
        }
        return out.toString();
    }
}
