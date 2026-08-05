package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "deposit_contract_special_term_agreements")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContractSpecialTermAgreement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long specialAgreementId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "special_term_id", nullable = false)
    private Long specialTermId;

    @Column(name = "is_agreed", nullable = false)
    private Boolean isAgreed;

    @Column(name = "agreed_at", columnDefinition = "CHAR(8)")
    private String agreedAt;

    @Column(name = "agreement_ip_address", length = 45)
    private String agreementIpAddress;

    @Column(name = "agreement_device_info", length = 255)
    private String agreementDeviceInfo;

    @Column(name = "is_electronic_signed", nullable = false)
    @Builder.Default
    private Boolean isElectronicSigned = false;

    @Column(name = "is_agreement_withdrawn", nullable = false)
    @Builder.Default
    private Boolean isAgreementWithdrawn = false;

    @Column(name = "agreement_withdrawn_at", columnDefinition = "CHAR(8)")
    private String agreementWithdrawnAt;

    public void withdraw(String withdrawnAt) {
        this.isAgreementWithdrawn = true;
        this.agreementWithdrawnAt = withdrawnAt;
    }
}
