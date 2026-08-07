package com.bank.customer.party.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개인관계자 상세정보. party_person 테이블 매핑.
 * party_id 는 party.party_id 와 동일한 공유 PK — @GeneratedValue 없이 서비스 레이어에서 직접 설정.
 * rrn_encrypted: AES-256 암호화 후 Base64 인코딩 문자열. 암복호화는 서비스 레이어 담당.
 */
@Getter
@Entity
@Table(name = "party_person")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PartyPerson extends BaseEntity {

    /** party.party_id 와 동일값. ID 생성 전략 없음 — 서비스에서 Party 저장 후 값 복사. */
    @Id
    @Column(name = "party_id")
    private Long partyId;

    /** AES-256 암호화 후 Base64 저장. 복호화는 CryptoService 경유. */
    @Column(name = "rrn_encrypted", length = 255)
    private String rrnEncrypted;

    /** 본인확인기관 연계정보 (CI). */
    @Column(name = "ci_value", length = 88)
    private String ciValue;

    @Column(name = "nationality_type_code", length = 20)
    private String nationalityTypeCode;

    @Column(name = "nationality_code", length = 3)
    private String nationalityCode;

    /** YYYYMMDD */
    @Column(name = "birth_date", columnDefinition = "CHAR(8)")
    private String birthDate;

    /** M / F / U */
    @Column(name = "gender_code", columnDefinition = "CHAR(1)")
    private String genderCode;

    @Column(name = "marital_status_code", length = 10)
    private String maritalStatusCode;

    @Column(name = "dependent_count")
    private Integer dependentCount;

    @Column(name = "occupation_code", length = 10)
    private String occupationCode;

    @Column(name = "occupation_name", length = 100)
    private String occupationName;

    @Column(name = "workplace_name", length = 200)
    private String workplaceName;

    @Column(name = "annual_income_amount")
    private Long annualIncomeAmount;

    @Column(name = "income_proof_code", length = 10)
    private String incomeProofCode;

    @Column(name = "capacity_limit_type_code", length = 20)
    private String capacityLimitTypeCode;

    /** T / F */
    @Column(name = "is_pep_yn", nullable = false)
    private Boolean isPepYn;

    @Column(name = "pep_type_code", length = 10)
    private String pepTypeCode;

    @Column(name = "pep_country_code", length = 3)
    private String pepCountryCode;

    @Column(name = "pep_position", length = 200)
    private String pepPosition;

    /** YYYYMMDD */
    @Column(name = "death_date", columnDefinition = "CHAR(8)")
    private String deathDate;

    public void updateCiValue(String ciValue) {
        this.ciValue = ciValue;
    }

    public void updatePersonalInfo(String occupationCode, String occupationName,
                                   String workplaceName, Long annualIncomeAmount,
                                   String incomeProofCode, String maritalStatusCode) {
        this.occupationCode      = occupationCode;
        this.occupationName      = occupationName;
        this.workplaceName       = workplaceName;
        this.annualIncomeAmount  = annualIncomeAmount;
        this.incomeProofCode     = incomeProofCode;
        this.maritalStatusCode   = maritalStatusCode;
    }

    public boolean isPep() {
        return Boolean.TRUE.equals(isPepYn);
    }
}
