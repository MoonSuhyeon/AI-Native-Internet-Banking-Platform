package com.bank.customer.device.domain;

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

@Entity
@Table(name = "registered_device")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RegisteredDevice extends BaseEntity {

    public static final String TYPE_MOBILE = "MOBILE";
    public static final String TYPE_PC     = "PC";
    public static final String TYPE_TABLET = "TABLET";

    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_REVOKED   = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "device_type_code", nullable = false, length = 20)
    private String deviceTypeCode;

    @Column(name = "device_os_name", length = 50)
    private String deviceOsName;

    @Column(name = "device_os_version", length = 50)
    private String deviceOsVersion;

    /** User-Agent 또는 디바이스 고유값의 SHA-256 해시 */
    @Column(name = "device_fingerprint_hash", nullable = false, length = 255)
    private String deviceFingerprintHash;

    /** 'T' = 신뢰 기기 (추가 인증 생략 가능) */
    @Column(name = "trusted_device_yn", nullable = false, length = 1)
    private Boolean trustedDeviceYn;

    /** 'T' = 지정 PC (인터넷뱅킹 보안 강화용) */
    @Column(name = "designated_pc_yn", nullable = false, length = 1)
    private Boolean designatedPcYn;

    @Column(name = "device_registered_ip", nullable = false, length = 45)
    private String deviceRegisteredIp;

    @Column(name = "device_last_used_at")
    private OffsetDateTime deviceLastUsedAt;

    @Column(name = "device_status_code", nullable = false, length = 20)
    private String deviceStatusCode;

    public boolean isActive()  { return STATUS_ACTIVE.equals(deviceStatusCode); }
    public boolean isTrusted() { return Boolean.TRUE.equals(trustedDeviceYn); }

    public void trust()    { this.trustedDeviceYn = true; }
    public void untrust()  { this.trustedDeviceYn = false; }
    public void designate()   { this.designatedPcYn = true; }
    public void undesignate() { this.designatedPcYn = false; }
    public void revoke()   { this.deviceStatusCode = STATUS_REVOKED; }
    public void suspend()  { this.deviceStatusCode = STATUS_SUSPENDED; }
    public void activate() { this.deviceStatusCode = STATUS_ACTIVE; }

    public void recordUsed() { this.deviceLastUsedAt = OffsetDateTime.now(); }
}
