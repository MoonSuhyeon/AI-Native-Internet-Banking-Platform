package com.bank.loan.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateBusinessCalendarRequest(
        Boolean businessDayYn,
        @Size(max = 50) String holidayTypeCd,
        @Size(max = 100) String holidayName,
        @Size(max = 10) String baseCountryCd
) {
}
