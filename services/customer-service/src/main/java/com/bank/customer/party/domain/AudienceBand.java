package com.bank.customer.party.domain;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.Period;

/**
 * 안내 문구를 맞출 대상 구간.
 *
 * <p><b>왜 생년월일이 아니라 구간인가.</b> 안내문을 만드는 쪽(조사 에이전트)이 알아야
 * 하는 것은 "이 사람에게 어떤 말투와 난이도로 설명할까" 뿐이다. 생년월일 자체는
 * 필요 없고, 넘기면 <b>필요 없는 개인정보가 서비스 경계를 하나 더 넘는다.</b>
 * 넘긴 값은 로그·트레이스·LLM 프롬프트에 남는다 — 그 전부가 유출 지점이 된다.
 *
 * <p>그래서 경계에서 구간으로 줄여서 내보낸다. 되돌릴 수 없는 축약이고, 안내문을
 * 만드는 데는 충분하다.
 *
 * <p><b>왜 이 두 구간인가.</b> 보이스피싱 피해가 몰리는 쪽이다.
 *
 * <ul>
 *   <li><b>고령층</b> — 통보를 받아도 무엇을 해야 할지 판단하기 어렵다.
 *       짧은 문장과 <b>전화 확인</b> 한 가지에 집중한 안내가 필요하다</li>
 *   <li><b>사회초년생</b> — 취업·대출 사기의 표적이다. "기관을 사칭한다" 는
 *       사실 자체를 모르는 경우가 많다</li>
 * </ul>
 *
 * <p><b>모르면 모른다고 한다.</b> 생년월일이 없거나 깨졌을 때 조용히 일반으로
 * 떨어뜨리지 않는다. {@link #UNKNOWN} 은 "일반 성인" 과 다른 사실이고, 나중에
 * "맞춤 안내가 얼마나 적용됐나" 를 셀 때 둘을 섞으면 커버리지가 부풀려진다.
 */
public enum AudienceBand {

    /** 만 19세 미만. */
    MINOR,

    /** 만 19~29세 — 사회초년생. */
    YOUNG_ADULT,

    /** 만 30~64세. */
    GENERAL,

    /** 만 65세 이상 — 고령층. */
    SENIOR,

    /** 생년월일을 알 수 없다. 일반과 섞지 않는다. */
    UNKNOWN;

    private static final int YOUNG_ADULT_UNTIL = 29;
    private static final int SENIOR_FROM = 65;
    private static final int ADULT_FROM = 19;

    /**
     * {@code YYYYMMDD} 문자열에서 구간을 정한다.
     *
     * <p>기준일을 인자로 받는 이유는 <b>테스트가 시간을 고정할 수 있어야</b> 하기
     * 때문이다. {@code LocalDate.now()} 를 안에서 부르면 생일 경계를 검증하는 시험이
     * 그 날짜에만 의미를 갖는다.
     */
    public static AudienceBand fromBirthDate(String yyyymmdd, LocalDate today) {
        if (yyyymmdd == null || yyyymmdd.isBlank() || today == null) {
            return UNKNOWN;
        }
        LocalDate birth;
        try {
            birth = LocalDate.of(
                    Integer.parseInt(yyyymmdd.substring(0, 4)),
                    Integer.parseInt(yyyymmdd.substring(4, 6)),
                    Integer.parseInt(yyyymmdd.substring(6, 8)));
        } catch (NumberFormatException | IndexOutOfBoundsException | DateTimeException e) {
            return UNKNOWN;
        }
        if (birth.isAfter(today)) {
            // 미래 생년월일은 데이터 오류다. 계산하면 음수 나이가 나오고
            // 그건 MINOR 로 읽혀 엉뚱한 안내가 나간다.
            return UNKNOWN;
        }

        int age = Period.between(birth, today).getYears();
        if (age >= SENIOR_FROM) {
            return SENIOR;
        }
        if (age < ADULT_FROM) {
            return MINOR;
        }
        return age <= YOUNG_ADULT_UNTIL ? YOUNG_ADULT : GENERAL;
    }
}
