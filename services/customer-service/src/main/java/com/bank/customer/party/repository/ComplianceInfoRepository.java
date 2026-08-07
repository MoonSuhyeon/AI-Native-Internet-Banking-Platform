package com.bank.customer.party.repository;

import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.dto.EddPendingResponse;
import com.bank.customer.party.dto.FatcaReportableResponse;
import com.bank.customer.party.dto.KycExpiringResponse;
import com.bank.customer.party.dto.SanctionedPartyResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ComplianceInfoRepository extends JpaRepository<ComplianceInfo, Long> {

    Optional<ComplianceInfo> findByPartyIdAndDeletedAtIsNull(Long partyId);

    /**
     * EDD 심사 대기 목록 — edd_required_yn=true. 이름은 party 도메인이라 Party 엔티티 조인으로 가져온다.
     * 식별자는 partyId(컴플라이언스 액션이 모두 partyId 기준). party_id 역순 고정 정렬.
     */
    @Query(value = """
            SELECT new com.bank.customer.party.dto.EddPendingResponse(
                ci.partyId, p.partyName, ci.amlRiskLevelCode, ci.cddLevelCode,
                ci.kycStatusCode, ci.eddNextReviewDate)
            FROM ComplianceInfo ci JOIN Party p ON p.partyId = ci.partyId
            WHERE ci.eddRequiredYn = true
              AND ci.deletedAt IS NULL
            ORDER BY ci.partyId DESC
            """,
            countQuery = """
            SELECT COUNT(ci)
            FROM ComplianceInfo ci JOIN Party p ON p.partyId = ci.partyId
            WHERE ci.eddRequiredYn = true
              AND ci.deletedAt IS NULL
            """)
    Page<EddPendingResponse> searchEddPending(Pageable pageable);

    /** 제재 대상자 목록 (직원 모니터링용) */
    @Query("""
            SELECT c FROM ComplianceInfo c
            WHERE c.isSanctionedYn = true
              AND c.deletedAt IS NULL
            """)
    List<ComplianceInfo> findAllSanctioned();

    /**
     * 제재대상 스크리닝 목록 — OFAC·UN·EU·KR 중 하나라도 제재인 party를 이름·인적사항과 함께 반환한다.
     * is_sanctioned_yn(GENERATED)이 아닌 원천 플래그 OR로 필터한다(소스 오브 트루스이자 H2 검증 가능).
     * party_id 역순 고정 정렬.
     */
    @Query(value = """
            SELECT new com.bank.customer.party.dto.SanctionedPartyResponse(
                ci.partyId, p.partyName, pp.birthDate, pp.nationalityCode,
                ci.isOfacSanctionedYn, ci.isUnSanctionedYn, ci.isEuSanctionedYn, ci.isKrSanctionedYn,
                ci.amlRiskLevelCode, ci.sanctionLastScreenedAt)
            FROM ComplianceInfo ci
            JOIN Party p ON p.partyId = ci.partyId
            LEFT JOIN PartyPerson pp ON pp.partyId = ci.partyId
            WHERE (ci.isOfacSanctionedYn = true OR ci.isUnSanctionedYn = true
                   OR ci.isEuSanctionedYn = true OR ci.isKrSanctionedYn = true)
              AND ci.deletedAt IS NULL
            ORDER BY ci.partyId DESC
            """,
            countQuery = """
            SELECT COUNT(ci)
            FROM ComplianceInfo ci
            WHERE (ci.isOfacSanctionedYn = true OR ci.isUnSanctionedYn = true
                   OR ci.isEuSanctionedYn = true OR ci.isKrSanctionedYn = true)
              AND ci.deletedAt IS NULL
            """)
    Page<SanctionedPartyResponse> searchSanctioned(Pageable pageable);

    /** KYC 만료 예정 목록 */
    @Query("""
            SELECT c FROM ComplianceInfo c
            WHERE c.kycExpiryDate <= :targetDate
              AND c.kycStatusCode = 'COMPLETED'
              AND c.deletedAt IS NULL
            """)
    List<ComplianceInfo> findKycExpiringBefore(
            @org.springframework.data.repository.query.Param("targetDate") String targetDate);

    /**
     * FATCA/CRS 보고대상 목록 — fatca_reportable_yn=true OR crs_reportable_yn=true.
     * 이름·인적사항을 Party·PartyPerson 조인으로 가져온다. party_id 역순 고정 정렬.
     */
    @Query(value = """
            SELECT new com.bank.customer.party.dto.FatcaReportableResponse(
                ci.partyId, p.partyName, pp.birthDate, pp.nationalityCode,
                ci.fatcaStatusCode, ci.fatcaReportableYn, ci.crsStatusCode, ci.crsReportableYn,
                ci.fatcaLastReviewedAt)
            FROM ComplianceInfo ci
            JOIN Party p ON p.partyId = ci.partyId
            LEFT JOIN PartyPerson pp ON pp.partyId = ci.partyId
            WHERE (ci.fatcaReportableYn = true OR ci.crsReportableYn = true)
              AND ci.deletedAt IS NULL
            ORDER BY ci.partyId DESC
            """,
            countQuery = """
            SELECT COUNT(ci)
            FROM ComplianceInfo ci
            WHERE (ci.fatcaReportableYn = true OR ci.crsReportableYn = true)
              AND ci.deletedAt IS NULL
            """)
    Page<FatcaReportableResponse> searchFatcaCrsReportable(Pageable pageable);

    /**
     * KYC 만료 예정 목록(페이지네이션·이름조인) — kyc_status='COMPLETED'이며 만료일이 기준일 이하.
     * 만료 임박 순(만료일 오름차순) 정렬.
     */
    @Query(value = """
            SELECT new com.bank.customer.party.dto.KycExpiringResponse(
                ci.partyId, p.partyName, ci.kycStatusCode, ci.kycExpiryDate, ci.kycNextReviewDate)
            FROM ComplianceInfo ci JOIN Party p ON p.partyId = ci.partyId
            WHERE ci.kycExpiryDate <= :targetDate
              AND ci.kycStatusCode = 'COMPLETED'
              AND ci.deletedAt IS NULL
            ORDER BY ci.kycExpiryDate ASC
            """,
            countQuery = """
            SELECT COUNT(ci)
            FROM ComplianceInfo ci
            WHERE ci.kycExpiryDate <= :targetDate
              AND ci.kycStatusCode = 'COMPLETED'
              AND ci.deletedAt IS NULL
            """)
    Page<KycExpiringResponse> searchKycExpiring(
            @org.springframework.data.repository.query.Param("targetDate") String targetDate,
            Pageable pageable);
}
