package com.bank.customer.party.repository;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.dto.SanctionedPartyResponse;
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
 * {@link ComplianceInfoRepository#searchSanctioned} 검증 — 제재대상 Hit 검토 화면의 진입점.
 *
 * <p>OFAC·UN·EU·KR 중 하나라도 제재인 party만 이름·인적사항과 함께 반환하는지, 비제재는 제외되는지,
 * 페이지네이션이 동작하는지 확인한다. is_sanctioned_yn(GENERATED) 대신 원천 플래그 OR로 필터하므로
 * H2에서도 검증 가능하다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("ComplianceInfoRepository.searchSanctioned — 제재대상 목록")
class SanctionedRepositoryTest {

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

    private void person(Long partyId, String birthDate, String nationality) {
        PartyPerson pp = PartyPerson.builder()
                .partyId(partyId)
                .birthDate(birthDate)
                .nationalityCode(nationality)
                .isPepYn(false)
                .build();
        em.persist(pp);
    }

    /** 제재 플래그(ofac/un/eu/kr)를 지정해 컴플라이언스 1건을 party와 함께 생성한다. */
    private Party compliance(String name, Boolean ofac, Boolean un, Boolean eu, Boolean kr) {
        Party p = party(name);
        ComplianceInfo ci = ComplianceInfo.builder()
                .partyId(p.getPartyId())
                .amlRiskLevelCode(ComplianceInfo.AML_HIGH)
                .isOfacSanctionedYn(ofac)
                .isUnSanctionedYn(un)
                .isEuSanctionedYn(eu)
                .isKrSanctionedYn(kr)
                .kycStatusCode(ComplianceInfo.KYC_COMPLETED)
                .cddLevelCode(ComplianceInfo.CDD_STANDARD)
                .eddRequiredYn(false)
                .fatcaStatusCode(ComplianceInfo.FATCA_EXEMPT)
                .fatcaReportableYn(false)
                .crsStatusCode(ComplianceInfo.CRS_EXEMPT)
                .crsReportableYn(false)
                .build();
        em.persist(ci);
        return p;
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("제재 플래그가 있는 party만 이름·인적사항과 함께 반환한다")
    void returnsOnlySanctioned_withIdentity() {
        Party ofac = compliance("제재대상", true, false, false, false);
        person(ofac.getPartyId(), "19850315", "KR");
        compliance("정상고객", false, false, false, false);
        em.flush();

        Page<SanctionedPartyResponse> result = complianceInfoRepository.searchSanctioned(FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        SanctionedPartyResponse row = result.getContent().get(0);
        assertThat(row.partyName()).isEqualTo("제재대상");
        assertThat(row.ofacSanctionedYn()).isTrue();
        assertThat(row.birthDate()).isEqualTo("19850315");
        assertThat(row.nationalityCode()).isEqualTo("KR");
    }

    @Test
    @DisplayName("OFAC 외 UN·EU·KR 어느 하나라도 제재면 포함된다")
    void anyOfFourFlags_included() {
        compliance("UN제재", false, true, false, false);
        compliance("EU제재", false, false, true, false);
        compliance("KR제재", false, false, false, true);
        compliance("정상", false, false, false, false);
        em.flush();

        Page<SanctionedPartyResponse> result = complianceInfoRepository.searchSanctioned(FIRST_20);

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).extracting(SanctionedPartyResponse::partyName)
                .containsExactlyInAnyOrder("UN제재", "EU제재", "KR제재");
    }

    @Test
    @DisplayName("party_person이 없어도(법인) 제재 목록에는 노출되고 인적사항은 null이다")
    void noPartyPerson_stillListed() {
        compliance("법인제재", true, false, false, false);
        em.flush();

        Page<SanctionedPartyResponse> result = complianceInfoRepository.searchSanctioned(FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("법인제재");
        assertThat(result.getContent().get(0).birthDate()).isNull();
    }
}
