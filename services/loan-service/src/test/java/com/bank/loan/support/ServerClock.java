package com.bank.loan.support;

import com.bank.common.time.BusinessDate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 날짜 경계 테스트용 고정 clock.
 *
 * <p><b>왜 존이 UTC 인가.</b> 이게 이 헬퍼의 요점이다. 지금 날짜 계산의 방어선은 두 겹이다.
 * <ol>
 *   <li>{@code ClockConfig} 가 KST clock 을 준다 → {@code now} 가 +09:00 을 달고 온다</li>
 *   <li>{@code BusinessDate.dateOf(now)} 가 어떤 오프셋이 오든 KST 로 변환한다</li>
 * </ol>
 * KST clock 으로 테스트하면 (1)에 가려 (2)가 검증되지 않는다. 실제로 처음 그렇게 썼다가
 * 옛 구현({@code now.toLocalDate()})에 돌려보니 5개 중 4개가 그냥 통과했다.
 *
 * <p>그래서 <b>일부러 UTC 존</b> clock 을 준다. 컨테이너 기본값이자, 누군가
 * {@code ClockConfig} 를 되돌렸을 때의 모습이다. 그 상태에서도 날짜가 KST 로 나와야
 * (2)가 살아 있는 것이다.
 */
public final class ServerClock {

    private ServerClock() {
    }

    /** 주어진 KST 벽시계 시각을 가리키되 존은 UTC 인 clock. 예: {@code "2026-08-07T08:30:00"} */
    public static Clock atKst(String kstWallClock) {
        return Clock.fixed(
                LocalDateTime.parse(kstWallClock).atZone(BusinessDate.ZONE).toInstant(),
                ZoneOffset.UTC);
    }

    /** 운영이 실제로 만들어내는 모양의 {@code now} — UTC 오프셋을 단 절대시각. */
    public static OffsetDateTime nowAtKst(String kstWallClock) {
        return OffsetDateTime.now(atKst(kstWallClock));
    }
}
