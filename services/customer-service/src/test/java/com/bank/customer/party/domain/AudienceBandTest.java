package com.bank.customer.party.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안내 대상 구간 판정.
 *
 * <p><b>왜 경계를 하나하나 보는가.</b> 이 값이 틀리면 화면에 <b>엉뚱한 말투의
 * 안내</b>가 뜬다. 오류로 드러나지 않고 그냥 덜 맞는 안내가 나가므로, 틀린 채로
 * 오래 간다. 특히 고령층 경계가 하루 어긋나면 65세 생일 당일에만 잘못 나오는데,
 * 그런 결함은 운영에서 절대 안 잡힌다.
 *
 * <p>기준일을 고정해서 본다. {@code LocalDate.now()} 에 기대면 이 시험 자체가
 * 오늘 날짜에만 의미를 갖는다.
 */
class AudienceBandTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 22);

    @Nested
    @DisplayName("고령층 경계")
    class SeniorBoundary {

        @Test
        @DisplayName("65세 생일 당일이면 고령층이다")
        void onSixtyFifthBirthday() {
            assertThat(AudienceBand.fromBirthDate("19610822", TODAY))
                    .isEqualTo(AudienceBand.SENIOR);
        }

        @Test
        @DisplayName("65세 생일 하루 전이면 아직 일반이다")
        void oneDayBeforeSixtyFifth() {
            assertThat(AudienceBand.fromBirthDate("19610823", TODAY))
                    .as("생일이 내일이면 아직 64세다. 하루 차이로 안내 말투가 "
                        + "바뀌는 자리라 경계를 정확히 봐야 한다")
                    .isEqualTo(AudienceBand.GENERAL);
        }
    }

    @Nested
    @DisplayName("사회초년생 경계")
    class YoungAdultBoundary {

        @Test
        @DisplayName("만 19세면 사회초년생이다")
        void nineteen() {
            assertThat(AudienceBand.fromBirthDate("20070822", TODAY))
                    .isEqualTo(AudienceBand.YOUNG_ADULT);
        }

        @Test
        @DisplayName("만 29세까지 사회초년생이다")
        void twentyNine() {
            assertThat(AudienceBand.fromBirthDate("19970822", TODAY))
                    .isEqualTo(AudienceBand.YOUNG_ADULT);
        }

        @Test
        @DisplayName("만 30세면 일반이다")
        void thirty() {
            assertThat(AudienceBand.fromBirthDate("19960822", TODAY))
                    .isEqualTo(AudienceBand.GENERAL);
        }

        @Test
        @DisplayName("만 19세 미만은 미성년이다")
        void minor() {
            assertThat(AudienceBand.fromBirthDate("20080101", TODAY))
                    .isEqualTo(AudienceBand.MINOR);
        }
    }

    @Nested
    @DisplayName("모를 때는 모른다고 한다")
    class UnknownStaysUnknown {

        @Test
        @DisplayName("생년월일이 없으면 UNKNOWN — 일반으로 떨어뜨리지 않는다")
        void missing() {
            assertThat(AudienceBand.fromBirthDate(null, TODAY)).isEqualTo(AudienceBand.UNKNOWN);
            assertThat(AudienceBand.fromBirthDate("  ", TODAY)).isEqualTo(AudienceBand.UNKNOWN);
        }

        @Test
        @DisplayName("형식이 깨졌으면 UNKNOWN")
        void malformed() {
            assertThat(AudienceBand.fromBirthDate("1961", TODAY)).isEqualTo(AudienceBand.UNKNOWN);
            assertThat(AudienceBand.fromBirthDate("19611301", TODAY))
                    .as("13월은 없다. 예외가 새어 나가면 안내 생성이 통째로 실패한다")
                    .isEqualTo(AudienceBand.UNKNOWN);
            assertThat(AudienceBand.fromBirthDate("abcdefgh", TODAY)).isEqualTo(AudienceBand.UNKNOWN);
        }

        @Test
        @DisplayName("미래 생년월일은 UNKNOWN — 음수 나이가 미성년으로 읽히면 안 된다")
        void future() {
            assertThat(AudienceBand.fromBirthDate("20301231", TODAY))
                    .isEqualTo(AudienceBand.UNKNOWN);
        }
    }
}
