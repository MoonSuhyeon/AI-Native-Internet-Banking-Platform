package com.bank.customer.fds.domain;

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

import java.time.OffsetDateTime;

/**
 * FDS 사고 처리 (fds_incident 테이블).
 * CONFIRMED 탐지에 대해 직원이 사고를 접수하고 처리하는 워크플로우.
 */
@Entity
@Table(name = "fds_incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FdsIncident {

    public static final String TYPE_ACCOUNT_TAKEOVER = "ACCOUNT_TAKEOVER";
    public static final String TYPE_CREDENTIAL_ABUSE = "CREDENTIAL_ABUSE";
    public static final String TYPE_UNAUTHORIZED_ACCESS = "UNAUTHORIZED_ACCESS";

    public static final String PROCESS_STATUS_OPEN       = "OPEN";
    public static final String PROCESS_STATUS_PROCESSING = "PROCESSING";
    public static final String PROCESS_STATUS_CLOSED     = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fds_incident_id")
    private Long fdsIncidentId;

    @Column(name = "fds_detection_id", nullable = false)
    private Long fdsDetectionId;

    /** 처리 직원 ID. null = 자동 생성(미배정) */
    @Column(name = "fds_incident_handler_employee_id")
    private Long fdsIncidentHandlerEmployeeId;

    @Column(name = "fds_incident_type_code", nullable = false, length = 20)
    private String fdsIncidentTypeCode;

    @Column(name = "fds_incident_process_status_code", nullable = false, length = 20)
    private String fdsIncidentProcessStatusCode;

    /** 'T' = 금감원 신고 완료, 'F' = 미신고 */
    @Column(name = "fds_incident_fss_reported_yn", nullable = false, length = 1)
    private Boolean fdsIncidentFssReportedYn;

    @Column(name = "fds_incident_reported_at")
    private OffsetDateTime fdsIncidentReportedAt;

    @Column(name = "fds_incident_closed_at")
    private OffsetDateTime fdsIncidentClosedAt;

    public void assignHandler(Long employeeId) {
        this.fdsIncidentHandlerEmployeeId = employeeId;
        this.fdsIncidentProcessStatusCode = PROCESS_STATUS_PROCESSING;
    }

    public void close() {
        this.fdsIncidentProcessStatusCode = PROCESS_STATUS_CLOSED;
        this.fdsIncidentClosedAt          = OffsetDateTime.now();
    }

    public void reportToFss() {
        this.fdsIncidentFssReportedYn  = true;
        this.fdsIncidentReportedAt     = OffsetDateTime.now();
    }

    public boolean isClosed() {
        return PROCESS_STATUS_CLOSED.equals(fdsIncidentProcessStatusCode);
    }
}
