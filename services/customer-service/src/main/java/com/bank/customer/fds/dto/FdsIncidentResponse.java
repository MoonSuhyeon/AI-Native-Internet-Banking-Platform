package com.bank.customer.fds.dto;

import com.bank.customer.fds.domain.FdsIncident;

import java.time.OffsetDateTime;

public record FdsIncidentResponse(
        Long           fdsIncidentId,
        Long           fdsDetectionId,
        Long           handlerEmployeeId,
        String         fdsIncidentTypeCode,
        String         fdsIncidentProcessStatusCode,
        boolean        fssReported,
        OffsetDateTime fdsIncidentReportedAt,
        OffsetDateTime fdsIncidentClosedAt
) {
    public static FdsIncidentResponse from(FdsIncident i) {
        return new FdsIncidentResponse(
                i.getFdsIncidentId(), i.getFdsDetectionId(),
                i.getFdsIncidentHandlerEmployeeId(), i.getFdsIncidentTypeCode(),
                i.getFdsIncidentProcessStatusCode(),
                Boolean.TRUE.equals(i.getFdsIncidentFssReportedYn()),
                i.getFdsIncidentReportedAt(), i.getFdsIncidentClosedAt());
    }
}
