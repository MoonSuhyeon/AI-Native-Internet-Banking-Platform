package com.bank.customer.party.repository;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.dto.EddPendingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * {@link ComplianceInfoRepository#searchEddPending} 검증 — EDD 심사·승인 화면의 진입점.
 *
 * <p>edd_required_yn='T'인 party만 이름과 함께 반환하는지(Compliance ⨝ Party 조인), 미대상은
 * 제외되는지, 페이지네이션이 동작하는지 확인한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("ComplianceInfoRepository.searchEddPending — EDD 심사 대기 목록")
class EddPendingRepositoryTest {

    private static final Pageable FIRST_20 = PageRequest.of(0, 20);

    @Autowired
    TestEntityManager em;

    @Autowired
    ComplianceInfoRepository complianceInfoRepository;

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────────

    private Party party(String name) {
        Party p = Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL)
                .partyName(name)
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build();
        em.persist(p);
        return p;
    }

    /** edd_required_yn 플래그를 가진 컴플라이언스 1건을 party와 함께 생성한다. */
    private void compliance(String name, Boolean eddRequiredYn) {
        Party p = party(name);
        ComplianceInfo ci = ComplianceInfo.builder()
                .partyId(p.getPartyId())
                .amlRiskLevelCode(ComplianceInfo.AML_HIGH)
                .isOfacSanctionedYn(false)
                .isUnSanctionedYn(false)
                .isEuSanctionedYn(false)
                .isKrSanctionedYn(false)
                .kycStatusCode(ComplianceInfo.KYC_COMPLETED)
                .cddLevelCode(ComplianceInfo.CDD_ENHANCED)
                .eddRequiredYn(eddRequiredYn)
                .eddNextReviewDate("20260801")
                .fatcaStatusCode(ComplianceInfo.FATCA_EXEMPT)
                .fatcaReportableYn(false)
                .crsStatusCode(ComplianceInfo.CRS_EXEMPT)
                .crsReportableYn(false)
                .build();
        em.persist(ci);
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edd_required_yn='T'인 party만 이름과 함께 반환한다")
    void returnsOnlyEddRequired_withName() {
        compliance("EDD대상", true);
        compliance("일반고객", false);
        em.flush();

        Page<EddPendingResponse> result = complianceInfoRepository.searchEddPending(FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        EddPendingResponse row = result.getContent().get(0);
        assertThat(row.partyName()).isEqualTo("EDD대상");
        assertThat(row.amlRiskLevelCode()).isEqualTo(ComplianceInfo.AML_HIGH);
        assertThat(row.cddLevelCode()).isEqualTo(ComplianceInfo.CDD_ENHANCED);
        assertThat(row.eddNextReviewDate()).isEqualTo("20260801");
    }

    @Test
    @DisplayName("EDD 대상이 없으면 빈 페이지를 반환한다")
    void emptyWhenNoneRequired() {
        compliance("일반1", false);
        compliance("일반2", false);
        em.flush();

        Page<EddPendingResponse> result = complianceInfoRepository.searchEddPending(FIRST_20);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("페이지 크기를 넘으면 분할되고 총 개수는 유지된다")
    void pagination() {
        for (int i = 0; i < 3; i++) compliance("EDD" + i, true);
        em.flush();

        Page<EddPendingResponse> page0 = complianceInfoRepository.searchEddPending(PageRequest.of(0, 2));

        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.getTotalPages()).isEqualTo(2);
    }
}
