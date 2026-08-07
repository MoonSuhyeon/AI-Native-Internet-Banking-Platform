package com.bank.loan.collateral.domain;

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
 * 담보. ERD STAGE 4 COLLATERAL 매핑.
 */
@Getter
@Entity
@Table(name = "collateral")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Collateral extends BaseEntity {

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_EVALUATED  = "EVALUATED";
    public static final String STATUS_APPROVED   = "APPROVED";
    public static final String STATUS_RELEASED   = "RELEASED";
    public static final String STATUS_REJECTED   = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "col_id")
    private Long colId;

    @Column(name = "appl_id", nullable = false)
    private Long applId;

    @Column(name = "col_type_cd", nullable = false, length = 50)
    private String colTypeCd;

    @Column(name = "col_status_cd", nullable = false, length = 50)
    private String colStatusCd;

    @Column(name = "col_no", nullable = false, length = 30, unique = true)
    private String colNo;

    @Column(name = "col_name", length = 200)
    private String colName;

    @Column(name = "col_address", length = 500)
    private String colAddress;

    @Column(name = "col_registry_no", length = 100)
    private String colRegistryNo;

    @Column(name = "declared_value")
    private Long declaredValue;

    @Column(name = "currency_cd", nullable = false, length = 10)
    private String currencyCd;

    @Column(name = "ownership_type_cd", length = 50)
    private String ownershipTypeCd;

    @Column(name = "senior_lien_yn", nullable = false)
    private Boolean seniorLienYn;

    @Column(name = "senior_lien_amount")
    private Long seniorLienAmount;

    public boolean isReleased() {
        return STATUS_RELEASED.equals(colStatusCd);
    }

    public String currentStatus() {
        return colStatusCd;
    }

    /**
     * 부분 수정. null 인 인자는 해당 필드 미변경.
     * col_no / appl_id / col_status_cd 는 변경 불가 (식별자·상태 전이는 별도 메서드).
     */
    public void update(
            String colTypeCd,
            String colName, String colAddress, String colRegistryNo,
            Long declaredValue,
            String currencyCd, String ownershipTypeCd,
            Boolean seniorLienYn, Long seniorLienAmount
    ) {
        if (colTypeCd != null) this.colTypeCd = colTypeCd;
        if (colName != null) this.colName = colName;
        if (colAddress != null) this.colAddress = colAddress;
        if (colRegistryNo != null) this.colRegistryNo = colRegistryNo;
        if (declaredValue != null) this.declaredValue = declaredValue;
        if (currencyCd != null) this.currencyCd = currencyCd;
        if (ownershipTypeCd != null) this.ownershipTypeCd = ownershipTypeCd;
        if (seniorLienYn != null) this.seniorLienYn = seniorLienYn;
        if (seniorLienAmount != null) this.seniorLienAmount = seniorLienAmount;
    }

    public void release() {
        this.colStatusCd = STATUS_RELEASED;
    }
}
