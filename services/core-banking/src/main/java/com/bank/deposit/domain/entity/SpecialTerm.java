package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.SpecialTermStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "deposit_special_terms")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SpecialTerm extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long specialTermId;

    @Column(name = "special_term_name", length = 200, nullable = false)
    private String specialTermName;

    @Column(name = "special_term_content", columnDefinition = "TEXT", nullable = false)
    private String specialTermContent;

    @Column(name = "special_term_summary", columnDefinition = "TEXT")
    private String specialTermSummary;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(name = "is_electronic_agreement_allowed", nullable = false)
    @Builder.Default
    private Boolean isElectronicAgreementAllowed = true;

    @Column(name = "special_term_version", length = 20, nullable = false)
    private String specialTermVersion;

    @Column(name = "started_at", columnDefinition = "CHAR(8)")
    private String startedAt;

    @Column(name = "ended_at", columnDefinition = "CHAR(8)")
    private String endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SpecialTermStatus status = SpecialTermStatus.ACTIVE;

    @Column(name = "status_changed_at", columnDefinition = "CHAR(8)")
    private String statusChangedAt;

    public void changeStatus(SpecialTermStatus status, String statusChangedAt) {
        this.status = status;
        this.statusChangedAt = statusChangedAt;
    }

    public void update(String specialTermName, String specialTermContent, String specialTermVersion) {
        this.specialTermName = specialTermName;
        this.specialTermContent = specialTermContent;
        this.specialTermVersion = specialTermVersion;
    }
}
