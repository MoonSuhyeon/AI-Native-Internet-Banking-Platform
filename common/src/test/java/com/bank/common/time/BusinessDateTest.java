package com.bank.common.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업일자 계산 테스트.
 *
 * <p>이 결함은 하루 중 KST 00:00~09:00 에만 나타났다. 그 창을 벗어난 시각으로만 확인하면
 * 고치기 전에도 통과하므로, 창 안쪽과 경계를 직접 고정해서 본다.
 *
 * <p><b>입력을 UTC 오프셋으로 주는 이유.</b> 운영 코드는 {@code OffsetDateTime.now()} 로
 * 시각을 얻고 컨테이너 JVM 타임존은 UTC 다(compose 의 {@code TZ=UTC}). 즉 실제로 흘러
 * 들어오는 값의 오프셋은 +00:00 이다. 여기에 +09:00 짜리 값을 넣어 확인하면 옛 구현
 * ({@code at.toLocalDate()})도 통과해 버려서 아무것도 막지 못한다. 실제로 이 테스트를
 * 옛 구현에 돌려 5개 중 4개가 통과하는 것을 확인하고 입력을 바꿨다.
 */
class BusinessDateTest {

    /** 운영과 같은 모양의 입력: 절대시각을 UTC 오프셋으로 표현한 값. */
    private static OffsetDateTime asServerSees(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.ofHours(9))
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("KST 자정 직후 거래는 그날의 영업일자를 받는다 — UTC 로는 아직 어제다")
    void justAfterMidnightKst() {
        OffsetDateTime at = asServerSees(2026, 8, 7, 0, 30);

        assertThat(at.toLocalDate().toString())
                .as("전제 확인: 서버가 보는 날짜는 하루 전이다")
                .isEqualTo("2026-08-06");
        assertThat(BusinessDate.of(at)).isEqualTo("20260807");
    }

    @Test
    @DisplayName("KST 08:59 거래도 당일이다 — 09:00 직전까지가 어제로 밀리던 구간")
    void justBeforeNineKst() {
        assertThat(BusinessDate.of(asServerSees(2026, 8, 7, 8, 59))).isEqualTo("20260807");
    }

    @Test
    @DisplayName("KST 09:00 부터는 옛 구현도 맞았다 — 고친 뒤에도 그대로여야 한다")
    void atNineKst() {
        assertThat(BusinessDate.of(asServerSees(2026, 8, 7, 9, 0))).isEqualTo("20260807");
    }

    @Test
    @DisplayName("KST 23:59 거래는 다음 날로 넘어가지 않는다")
    void justBeforeMidnightKst() {
        assertThat(BusinessDate.of(asServerSees(2026, 8, 7, 23, 59))).isEqualTo("20260807");
    }

    @Test
    @DisplayName("같은 순간이면 어느 오프셋으로 표현했든 같은 영업일자다")
    void sameInstantDifferentOffset() {
        OffsetDateTime kst = OffsetDateTime.of(2026, 8, 7, 0, 30, 0, 0, ZoneOffset.ofHours(9));

        assertThat(BusinessDate.of(kst.withOffsetSameInstant(ZoneOffset.UTC)))
                .isEqualTo(BusinessDate.of(kst));
        assertThat(BusinessDate.of(kst.withOffsetSameInstant(ZoneOffset.ofHours(-4))))
                .isEqualTo(BusinessDate.of(kst));
    }

    @Test
    @DisplayName("영업일자는 yyyyMMdd 여덟 자리다 — 원장·정산 쿼리가 이 형식을 문자열로 비교한다")
    void format() {
        assertThat(BusinessDate.of(asServerSees(2026, 1, 2, 12, 0))).isEqualTo("20260102");
        assertThat(BusinessDate.today()).matches("\\d{8}");
    }
}
