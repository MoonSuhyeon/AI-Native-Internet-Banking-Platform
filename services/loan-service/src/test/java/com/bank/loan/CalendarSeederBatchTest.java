package com.bank.loan;

import com.bank.loan.calendar.repository.BusinessCalendarRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영업일 캘린더 자동 시드 배치 통합 테스트.
 *
 * 시드 연도: 2040 (윤년) — V9 시드 2026-2035 + 다른 배치 테스트(20300101 / 20350101) 와 격리.
 *
 * 시나리오:
 *   10) year=2040 시드 → inserted=366
 *   11) 신정 (20400101) — N, PUBLIC, 신정
 *   12) 크리스마스 (20401225) — N, PUBLIC, 크리스마스
 *   13) 토요일 (20400107) — N, WEEKEND
 *   14) 멱등성: 동일 year 재실행 → inserted=0, skipped=366
 *   15) year 범위 외 (1900) → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CalendarSeederBatchTest extends AbstractLoanIntegrationTest {

    @Autowired
    private BusinessCalendarRepository repository;

    private static final int SEED_YEAR = 2040;
    private static final int LEAP_YEAR_DAYS = 366;

    @Test @Order(10)
    void year_2040_시드_366일_INSERTED() throws Exception {
        mockMvc.perform(post("/api/internal/calendar-seeder/run").param("year", String.valueOf(SEED_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(SEED_YEAR))
                .andExpect(jsonPath("$.data.totalDays").value(LEAP_YEAR_DAYS))
                .andExpect(jsonPath("$.data.inserted").value(LEAP_YEAR_DAYS))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test @Order(11)
    void 신정_20400101_N_PUBLIC() {
        var cal = repository.findByCalDateAndDeletedAtIsNull("20400101").orElseThrow();
        assertThat(cal.getBusinessDayYn()).isFalse();
        assertThat(cal.getHolidayTypeCd()).isEqualTo("PUBLIC");
        assertThat(cal.getHolidayName()).isEqualTo("신정");
    }

    @Test @Order(12)
    void 크리스마스_20401225_N_PUBLIC() {
        var cal = repository.findByCalDateAndDeletedAtIsNull("20401225").orElseThrow();
        assertThat(cal.getBusinessDayYn()).isFalse();
        assertThat(cal.getHolidayTypeCd()).isEqualTo("PUBLIC");
        assertThat(cal.getHolidayName()).isEqualTo("크리스마스");
    }

    @Test @Order(13)
    void 토요일_20400107_N_WEEKEND() {
        // 2040-01-07 = 토요일
        var cal = repository.findByCalDateAndDeletedAtIsNull("20400107").orElseThrow();
        assertThat(cal.getBusinessDayYn()).isFalse();
        assertThat(cal.getHolidayTypeCd()).isEqualTo("WEEKEND");
    }

    @Test @Order(14)
    void 동일_year_재실행_멱등_skipped_366() throws Exception {
        mockMvc.perform(post("/api/internal/calendar-seeder/run").param("year", String.valueOf(SEED_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inserted").value(0))
                .andExpect(jsonPath("$.data.skipped").value(LEAP_YEAR_DAYS));
    }

    @Test @Order(15)
    void year_범위_외_400() throws Exception {
        mockMvc.perform(post("/api/internal/calendar-seeder/run").param("year", "1900"))
                .andExpect(status().isBadRequest());
    }
}
