package com.bank.loan.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterBusinessCalendarRequest(
        @NotBlank @Pattern(regexp = "\\d{8}") String calDate,
        Boolean businessDayYn,
        @Size(max = 50) String holidayTypeCd,
        @Size(max = 100) String holidayName,
        @Size(max = 10) String baseCountryCd
) {
}
