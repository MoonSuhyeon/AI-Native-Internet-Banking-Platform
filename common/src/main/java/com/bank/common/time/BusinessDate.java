package com.bank.common.time;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 영업일자(yyyyMMdd)를 한 곳에서 계산한다.
 *
 * <p><b>왜 공용 모듈에 있는가.</b> 같은 서비스 안에서도 정의가 갈려 있었다.
 * 결제계는 마감 배치가 {@code LocalDate.now(ZoneId.of("Asia/Seoul"))} 로 당일 정산 대상을
 * 고르는데 거래를 기록하는 쪽은 {@code OffsetDateTime.now().toLocalDate()} 를 썼고,
 * 여신계도 EOD/EOM 배치만 KST 를 주고 상환·연체 계산은 주지 않았다. 두 서비스가 각자
 * 자기 상수를 만들면 같은 어긋남이 다시 생기므로 정의를 하나만 둔다.
 *
 * <p><b>무엇이 틀렸었나.</b> 컨테이너 JVM 타임존은 UTC 다(compose 의 {@code TZ=UTC},
 * 미설정 서비스도 베이스 이미지 기본값이 UTC). 그래서 존을 주지 않고 날짜를 파생하면
 * UTC 날짜가 나온다. 한국 시간 00:00~09:00 은 UTC 로는 아직 어제이므로, 하루의 3분의 1
 * 동안 날짜가 하루 밀렸다.
 *
 * <p><b>왜 KST 고정인가.</b> 영업일은 은행이 영업하는 지역의 달력이다. 서버가 어디서 돌든
 * 한국 은행의 영업일은 KST 기준이므로 JVM 기본 타임존에 맡기지 않는다. 시각 자체는
 * 절대시각(TIMESTAMPTZ)으로 저장하고, 날짜 파생만 여기서 한다.
 *
 * <p><b>범위.</b> 이 클래스는 타임존만 다룬다. 실제 은행 영업일은 주말·공휴일·마감시각
 * (cut-off) 이 걸리므로 "오늘 날짜"와 "영업일"이 늘 같지는 않다. 그 판정은 별개이며
 * 여신계의 {@code BusinessDayService} 가 담당한다.
 */
public final class BusinessDate {

    /** 영업일 판정 기준 지역. 서버 타임존과 무관하게 고정한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private BusinessDate() {
    }

    /** 주어진 절대시각이 속한 영업일자를 yyyyMMdd 로 돌려준다. */
    public static String of(OffsetDateTime at) {
        return dateOf(at).format(FORMAT);
    }

    /** 주어진 절대시각이 속한 영업일. 날짜 계산(연체일수 등)이 필요할 때 쓴다. */
    public static LocalDate dateOf(OffsetDateTime at) {
        return at.atZoneSameInstant(ZONE).toLocalDate();
    }

    /** 오늘 영업일자(yyyyMMdd). */
    public static String today() {
        return todayDate().format(FORMAT);
    }

    /** 오늘 영업일. */
    public static LocalDate todayDate() {
        return LocalDate.now(ZONE);
    }
}
