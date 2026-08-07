package com.bank.customer.party.repository;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.dto.FatcaReportableResponse;
import com.bank.customer.party.dto.KycExpiringResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * compliance_info 재사용 목록 쿼리 검증 — FATCA/CRS 보고대상·KYC 만료예정 화면의 진입점.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("ComplianceInfoRepository — FATCA/CRS·KYC만료 목록")
class ComplianceListsRepositoryTest {

    private static final Pageable FIRST_20 = PageRequest.of(0, 20);

    @Autowired
    TestEntityManager em;

    @Autowired
    ComplianceInfoRepository complianceInfoRepository;

    private Party party(String name) {
        Party p = Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL)
                .partyName(name)
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build();
        em.persist(p);
        return p;
    }

    /** 가변 필드(FATCA/CRS 보고여부·KYC 상태·만료일)를 지정해 컴플라이언스 1건을 생성한다. */
    private void compliance(String name, Boolean fatcaReportable, Boolean crsReportable,
                            String kycStatus, String kycExpiry) {
        Party p = party(name);
        ComplianceInfo ci = ComplianceInfo.builder()
                .partyId(p.getPartyId())
                .amlRiskLevelCode(ComplianceInfo.AML_LOW)
                .isOfacSanctionedYn(false)
                .isUnSanctionedYn(false)
                .isEuSanctionedYn(false)
                .isKrSanctionedYn(false)
                .kycStatusCode(kycStatus)
                .kycExpiryDate(kycExpiry)
                .cddLevelCode(ComplianceInfo.CDD_STANDARD)
                .eddRequiredYn(false)
                .fatcaStatusCode(ComplianceInfo.FATCA_REPORTABLE)
                .fatcaReportableYn(fatcaReportable)
                .crsStatusCode(ComplianceInfo.CRS_REPORTABLE)
                .crsReportableYn(crsReportable)
                .build();
        em.persist(ci);
    }

    @Nested
    @DisplayName("searchFatcaCrsReportable")
    class Fatca {
        @Test
        @DisplayName("FATCA 또는 CRS 보고대상만 이름과 함께 반환한다")
        void onlyReportable() {
            compliance("FATCA대상", true, false, ComplianceInfo.KYC_COMPLETED, "20300101");
            compliance("CRS대상",   false, true, ComplianceInfo.KYC_COMPLETED, "20300101");
            compliance("비보고",    false, false, ComplianceInfo.KYC_COMPLETED, "20300101");
            em.flush();

            Page<FatcaReportableResponse> result = complianceInfoRepository.searchFatcaCrsReportable(FIRST_20);

            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent()).extracting(FatcaReportableResponse::partyName)
                    .containsExactlyInAnyOrder("FATCA대상", "CRS대상");
        }
    }

    @Nested
    @DisplayName("searchKycExpiring")
    class Kyc {
        @Test
        @DisplayName("기준일 이하 만료 + COMPLETED만 반환하고 만료 임박 순으로 정렬한다")
        void expiringBeforeTarget_completedOnly_sorted() {
            compliance("곧만료A", false, false, ComplianceInfo.KYC_COMPLETED, "20260701");
            compliance("곧만료B", false, false, ComplianceInfo.KYC_COMPLETED, "20260601");
            compliance("나중만료", false, false, ComplianceInfo.KYC_COMPLETED, "20270101");
            compliance("미완료",   false, false, ComplianceInfo.KYC_PENDING,   "20260601");
            em.flush();

            Page<KycExpiringResponse> result =
                    complianceInfoRepository.searchKycExpiring("20260801", FIRST_20);

            assertThat(result.getContent()).extracting(KycExpiringResponse::partyName)
                    .containsExactly("곧만료B", "곧만료A"); // 만료일 오름차순, 미완료·나중만료 제외
        }
    }
}
