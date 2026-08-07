package com.bank.loan.calendar.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 영업일 캘린더. ERD STAGE 1 BUSINESS_CALENDAR 매핑.
 *
 * cal_date 는 YYYYMMDD 8자리 문자열 (스키마 VARCHAR(8) UNIQUE).
 * business_day_yn 는 'Y' (영업일) / 'N' (비영업일/휴일).
 */
@Getter
@Entity
@Table(name = "business_calendar")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BusinessCalendar extends BaseEntity {


    public static final String COUNTRY_KR = "KR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cal_id")
    private Long calId;

    @Column(name = "cal_date", nullable = false, length = 8, unique = true)
    private String calDate;

    @Column(name = "business_day_yn", nullable = false)
    private Boolean businessDayYn;

    @Column(name = "holiday_type_cd", length = 50)
    private String holidayTypeCd;

    @Column(name = "holiday_name", length = 100)
    private String holidayName;

    @Column(name = "base_country_cd", length = 10)
    private String baseCountryCd;

    public boolean isBusinessDay() {
        return Boolean.TRUE.equals(businessDayYn);
    }

    public void update(Boolean businessDayYn, String holidayTypeCd, String holidayName, String baseCountryCd) {
        this.businessDayYn = businessDayYn;
        this.holidayTypeCd = holidayTypeCd;
        this.holidayName = holidayName;
        this.baseCountryCd = baseCountryCd;
    }
}
