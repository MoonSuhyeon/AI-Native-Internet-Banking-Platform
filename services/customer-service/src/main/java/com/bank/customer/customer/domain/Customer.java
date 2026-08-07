package com.bank.customer.customer.domain;

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

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Getter
@Entity
@Table(name = "customer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Customer extends BaseEntity {

    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_DORMANT   = "DORMANT";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CLOSED    = "CLOSED";

    public static final String GRADE_NORMAL = "NORMAL";
    public static final String GRADE_VIP    = "VIP";
    public static final String GRADE_PB     = "PB";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "customer_grade_code", length = 10)
    private String customerGradeCode;

    @Column(name = "credit_rating_code", length = 10)
    private String creditRatingCode;

    /** YYYYMMDD */
    @Column(name = "credit_evaluation_date", columnDefinition = "CHAR(8)")
    private String creditEvaluationDate;

    @Column(name = "credit_agency_code", length = 10)
    private String creditAgencyCode;

    @Column(name = "customer_status_code", nullable = false, length = 20)
    private String customerStatusCode;

    /** T / F */
    @Column(name = "main_customer_yn", nullable = false)
    private Boolean mainCustomerYn;

    @Column(name = "preferred_language_code", length = 2)
    private String preferredLanguageCode;

    /** T / F */
    @Column(name = "sms_receive_yn", nullable = false)
    private Boolean smsReceiveYn;

    /** T / F */
    @Column(name = "email_receive_yn", nullable = false)
    private Boolean emailReceiveYn;

    /** T / F */
    @Column(name = "postal_receive_yn", nullable = false)
    private Boolean postalReceiveYn;

    @Column(name = "notification_method_code", length = 10)
    private String notificationMethodCode;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "address_detail", length = 255)
    private String addressDetail;

    @Column(name = "join_channel_code", length = 20)
    private String joinChannelCode;

    /** YYYYMMDD — 재가입 시 불변 */
    @Column(name = "first_join_date", columnDefinition = "CHAR(8)")
    private String firstJoinDate;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "last_transaction_at")
    private OffsetDateTime lastTransactionAt;

    @Column(name = "dormant_at")
    private OffsetDateTime dormantAt;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "close_reason_code", length = 20)
    private String closeReasonCode;

    /** YYYYMMDD — closed_at + 5년 */
    @Column(name = "privacy_expiry_date", columnDefinition = "CHAR(8)")
    private String privacyExpiryDate;

    // -------------------------------------------------------------------------
    // 연락처·수신 설정 변경
    // -------------------------------------------------------------------------

    public void updateContact(String email, String phone,
                              String zipCode, String address, String addressDetail) {
        this.email        = email;
        this.phone        = phone;
        this.zipCode      = zipCode;
        this.address      = address;
        this.addressDetail = addressDetail;
    }

    public void updateNotification(Boolean smsReceiveYn, Boolean emailReceiveYn,
                                   Boolean postalReceiveYn, String notificationMethodCode) {
        this.smsReceiveYn            = smsReceiveYn;
        this.emailReceiveYn          = emailReceiveYn;
        this.postalReceiveYn         = postalReceiveYn;
        this.notificationMethodCode  = notificationMethodCode;
    }

    // -------------------------------------------------------------------------
    // 상태 전이
    // -------------------------------------------------------------------------

    public void dormant(OffsetDateTime dormantAt) {
        this.customerStatusCode = STATUS_DORMANT;
        this.dormantAt          = dormantAt;
    }

    /** 정지 처리(위험·규제 등에 의한 계정 동결). 해제는 {@link #reactivate()}. */
    public void suspend(OffsetDateTime suspendedAt) {
        this.customerStatusCode = STATUS_SUSPENDED;
        this.suspendedAt        = suspendedAt;
    }

    /** 해지 처리. 개인정보 보유기간 = 해지일 + 5년. */
    public void close(OffsetDateTime closedAt, String closeReasonCode) {
        this.customerStatusCode = STATUS_CLOSED;
        this.closedAt           = closedAt;
        this.closeReasonCode    = closeReasonCode;
        this.privacyExpiryDate  = closedAt.plusYears(5).format(DATE_FMT);
    }

    /** 휴면·정지 해제 → 활성 복귀. 해당 시점 컬럼을 함께 비운다. */
    public void reactivate() {
        this.customerStatusCode = STATUS_ACTIVE;
        this.dormantAt          = null;
        this.suspendedAt        = null;
    }

    public void updateGrade(String newGradeCode) {
        this.customerGradeCode = newGradeCode;
    }

    public void updateCreditRating(String ratingCode, String evaluationDate, String agencyCode) {
        this.creditRatingCode      = ratingCode;
        this.creditEvaluationDate  = evaluationDate;
        this.creditAgencyCode      = agencyCode;
    }

    public void recordTransaction(OffsetDateTime at) {
        this.lastTransactionAt = at;
    }

    // -------------------------------------------------------------------------
    // 상태 조회
    // -------------------------------------------------------------------------

    public boolean isActive() {
        return STATUS_ACTIVE.equals(customerStatusCode);
    }

    public boolean isDormant() {
        return STATUS_DORMANT.equals(customerStatusCode);
    }

    public boolean isSuspended() {
        return STATUS_SUSPENDED.equals(customerStatusCode);
    }

    public boolean isClosed() {
        return STATUS_CLOSED.equals(customerStatusCode);
    }
}
