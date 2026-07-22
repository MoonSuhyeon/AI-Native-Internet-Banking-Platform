package com.bank.ai.privacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingFilterTest {

    private final PiiMaskingFilter filter = new PiiMaskingFilter();

    @Test
    void 주민등록번호_마스킹된다() {
        var result = filter.mask("홍길동 900101-1234567 신청");
        assertThat(result.maskedText()).doesNotContain("900101-1234567");
        assertThat(result.mapping().values()).contains("900101-1234567");
    }

    @Test
    void 휴대전화_마스킹된다() {
        var result = filter.mask("연락처 010-1234-5678 입니다");
        assertThat(result.maskedText()).doesNotContain("010-1234-5678");
    }

    @Test
    void 카드번호_마스킹된다() {
        var result = filter.mask("결제 카드 4111-1111-1111-1111");
        assertThat(result.maskedText()).doesNotContain("4111-1111-1111-1111");
    }

    @Test
    void 이메일_마스킹된다() {
        var result = filter.mask("문의 user@example.com");
        assertThat(result.maskedText()).doesNotContain("user@example.com");
    }

    @Test
    void 동일_원문은_동일_토큰으로_치환된다() {
        var result = filter.mask("홍길동 거래내역. 홍길동 잔액 확인.");
        // '홍길동' 이라는 동일 원문이 두 번 등장해도 매핑은 한 개여야 한다.
        // (KOREAN_NAME 정규식은 NER 도입 전이라 다른 2~4자 한글 명사도 매칭하지만,
        //  여기서 검증하는 것은 '동일 원문 → 동일 토큰' 불변식.)
        long mappingsFor홍길동 = result.mapping().entrySet().stream()
                .filter(e -> e.getValue().equals("홍길동"))
                .count();
        assertThat(mappingsFor홍길동).isEqualTo(1);

        // 동일 토큰이 마스킹 결과에 두 번 나타나야 한다.
        String token = result.mapping().entrySet().stream()
                .filter(e -> e.getValue().equals("홍길동"))
                .map(java.util.Map.Entry::getKey)
                .findFirst().orElseThrow();
        int occurrences = result.maskedText().split(java.util.regex.Pattern.quote(token), -1).length - 1;
        assertThat(occurrences).isEqualTo(2);
    }

    @Test
    void 역치환으로_원문_복원된다() {
        String original = "홍길동 010-1234-5678 user@example.com 문의";
        var result = filter.mask(original);
        // LLM 이 마스킹 토큰을 그대로 echo 했다고 가정
        String restored = result.unmask(result.maskedText());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void 빈입력_안전처리() {
        assertThat(filter.mask(null).maskedText()).isNull();
        assertThat(filter.mask("").maskedText()).isEmpty();
        // 한글 명사가 없는 입력은 매핑이 비어야 한다.
        // (현재 KOREAN_NAME 정규식은 NER 도입 전이라 2~4자 한글 명사를 모두 잡으므로
        //  진정한 'PII 없음' 케이스는 영문/숫자만으로 검증.)
        assertThat(filter.mask("no PII here 12345").mapping()).isEmpty();
    }

    @Test
    void 계좌번호_마스킹된다() {
        var result = filter.mask("입금계좌 123-456-789012");
        assertThat(result.maskedText()).doesNotContain("123-456-789012");
    }
}
