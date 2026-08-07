package com.bank.loan.calendar.dto;

import com.bank.loan.calendar.domain.BusinessCalendar;

public record BusinessCalendarResponse(
        Long calId,
        String calDate,
        Boolean businessDayYn,
        String holidayTypeCd,
        String holidayName,
        String baseCountryCd
) {
    public static BusinessCalendarResponse of(BusinessCalendar c) {
        return new BusinessCalendarResponse(
                c.getCalId(),
                c.getCalDate(),
                c.getBusinessDayYn(),
                c.getHolidayTypeCd(),
                c.getHolidayName(),
                c.getBaseCountryCd()
        );
    }
}
