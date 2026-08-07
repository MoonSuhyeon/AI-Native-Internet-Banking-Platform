package com.bank.customer.party.dto;

import java.time.OffsetDateTime;

/**
 * FATCA/CRS 보고대상 행 — FATCA/CRS 화면(/admin/fatca)의 진입점.
 *
 * <p>compliance_info에서 fatca_reportable_yn='T' 또는 crs_reportable_yn='T'인 party를 이름·인적사항과
 * 함께 반환한다. 제재·EDD 목록과 동일하게 compliance_info를 재사용한다(신규 테이블 없음).
 */
public record FatcaReportableResponse(
        Long           partyId,
        String         partyName,
        String         birthDate,
        String         nationalityCode,
        String         fatcaStatusCode,
        Boolean         fatcaReportableYn,
        String         crsStatusCode,
        Boolean         crsReportableYn,
        OffsetDateTime fatcaLastReviewedAt
) {
}
