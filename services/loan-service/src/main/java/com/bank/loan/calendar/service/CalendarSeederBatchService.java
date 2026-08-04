package com.bank.loan.calendar.service;

import com.bank.loan.calendar.domain.BusinessCalendar;
import com.bank.loan.calendar.dto.CalendarSeederRunResponse;
import com.bank.loan.calendar.repository.BusinessCalendarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 영업일 캘린더 자동 시드 배치.
 *
 * V9 시드는 2026-2035 고정 → 2036 이상부터 캘린더가 비어 EOD 영업일 판정이 불가해진다.
 * 본 배치는 매년 12월 1일에 다음 해 1년치 캘린더를 자동 적재한다.
 *
 * 시드 규칙:
 *   - 주말 (Saturday/Sunday)            → N, type=WEEKEND
 *   - 양력 고정 공휴일 (FIXED_HOLIDAYS)  → N, type=PUBLIC
 *   - 그 외 평일                          → Y
 *
 * 멱등성: 이미 존재하는 cal_date 는 skip — UNIQUE 의존.
 *
 * 의식적 단순화 (운영자 추가 책임):
 *   - 음력 공휴일 (설날·추석·부처님오신날) — 매년 양력 환산 필요, 본 배치 범위 외
 *   - 대체공휴일 — 매년 확정 일정이 다름
 *   - 임시공휴일 — 정부 공고 의존
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarSeederBatchService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");

    /** 양력 고정 공휴일 (관공서의 공휴일에 관한 규정). */
    private static final Map<String, String> FIXED_HOLIDAYS = Map.of(
            "0101", "신정",
            "0301", "삼일절",
            "0505", "어린이날",
            "0606", "현충일",
            "0815", "광복절",
            "1003", "개천절",
            "1009", "한글날",
            "1225", "크리스마스"
    );

    private static final String TYPE_WEEKEND = "WEEKEND";
    private static final String TYPE_PUBLIC  = "PUBLIC";

    private final BusinessCalendarRepository repository;

    @Transactional
    public CalendarSeederRunResponse run(int year) {
        LocalDate firstDay = LocalDate.of(year, 1, 1);
        LocalDate lastDay  = LocalDate.of(year, 12, 31);

        int inserted = 0, skipped = 0, totalDays = 0;
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            totalDays++;
            String calDate = d.format(DATE);
            if (repository.findByCalDateAndDeletedAtIsNull(calDate).isPresent()) {
                skipped++;
                continue;
            }
            HolidayInfo info = resolveHoliday(d);
            repository.save(BusinessCalendar.builder()
                    .calDate(calDate)
                    .businessDayYn(info.businessDay ? BusinessCalendar.YN_Y : BusinessCalendar.YN_N)
                    .holidayTypeCd(info.typeCd)
                    .holidayName(info.name)
                    .baseCountryCd(BusinessCalendar.COUNTRY_KR)
                    .build());
            inserted++;
        }

        log.info("[calendar-seeder] year={} totalDays={} inserted={} skipped={}",
                year, totalDays, inserted, skipped);
        return CalendarSeederRunResponse.of(year, totalDays, inserted, skipped);
    }

    private HolidayInfo resolveHoliday(LocalDate d) {
        // 공휴일 판정이 주말보다 앞선다. 신정처럼 공휴일이 주말에 겹치면 어느 쪽이든
        // 비영업일(N)이라 영업일 계산은 같지만, WEEKEND/"일요일" 로 덮어쓰면 무슨 공휴일이었는지가
        // 사라져 대체공휴일 판단과 휴일 보고에서 쓸 수 없게 된다.
        String name = FIXED_HOLIDAYS.get(d.format(MMDD));
        if (name != null) {
            return new HolidayInfo(false, TYPE_PUBLIC, name);
        }
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return new HolidayInfo(false, TYPE_WEEKEND, dow == DayOfWeek.SATURDAY ? "토요일" : "일요일");
        }
        return new HolidayInfo(true, null, null);
    }

    private record HolidayInfo(boolean businessDay, String typeCd, String name) {}
}
