package com.bank.customer.party.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.PartyRelation;
import com.bank.customer.party.domain.PartyRole;
import com.bank.customer.party.dto.AddPartyRelationRequest;
import com.bank.customer.party.dto.PartyRelationResponse;
import com.bank.customer.party.dto.PartyRoleResponse;
import com.bank.customer.party.domain.DuplicateReviewCase;
import com.bank.customer.party.domain.SanctionScreeningHit;
import com.bank.customer.party.dto.DuplicateReviewResponse;
import com.bank.customer.party.dto.SanctionHitResponse;
import com.bank.customer.party.repository.ComplianceInfoRepository;
import com.bank.customer.party.repository.DuplicateReviewCaseRepository;
import com.bank.customer.party.repository.PartyPersonRepository;
import com.bank.customer.party.repository.PartyRelationRepository;
import com.bank.customer.party.repository.PartyRoleRepository;
import com.bank.customer.party.repository.SanctionScreeningHitRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PartyManageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PartyRoleRepository             partyRoleRepository;
    private final PartyRelationRepository         partyRelationRepository;
    private final ComplianceInfoRepository        complianceInfoRepository;
    private final PartyPersonRepository           partyPersonRepository;
    private final SanctionScreeningHitRepository  sanctionScreeningHitRepository;
    private final DuplicateReviewCaseRepository   duplicateReviewCaseRepository;
    private final CustomerRepository              customerRepository;

    // ── 역할 관리 ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PartyRoleResponse> getRoles(Long partyId) {
        return partyRoleRepository.findByPartyIdAndDeletedAtIsNull(partyId)
                .stream().map(PartyRoleResponse::from).toList();
    }

    @Transactional
    public PartyRoleResponse addRole(Long partyId, String roleTypeCode, String startDate) {
        partyRoleRepository.findByPartyIdAndRoleTypeCodeAndRoleStatusCodeAndDeletedAtIsNull(
                partyId, roleTypeCode, PartyRole.STATUS_ACTIVE)
                .ifPresent(r -> { throw new BusinessException(CustomerErrorCode.CUST_110); });

        PartyRole role = partyRoleRepository.save(PartyRole.builder()
                .partyId(partyId)
                .roleTypeCode(roleTypeCode)
                .roleStatusCode(PartyRole.STATUS_ACTIVE)
                .roleStartDate(startDate != null ? startDate : BusinessDate.today())
                .build());
        return PartyRoleResponse.from(role);
    }

    @Transactional
    public PartyRoleResponse closeRole(Long roleId, String endDate, String reasonCode) {
        PartyRole role = partyRoleRepository.findById(roleId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_111));
        role.close(endDate != null ? endDate : BusinessDate.today(), reasonCode);
        return PartyRoleResponse.from(role);
    }

    // ── 관계 관리 ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PartyRelationResponse> getRelations(Long partyId) {
        return partyRelationRepository.findAllActiveRelations(partyId)
                .stream().map(PartyRelationResponse::from).toList();
    }

    @Transactional
    public PartyRelationResponse addRelation(Long fromPartyId, AddPartyRelationRequest req) {
        if (fromPartyId.equals(req.toPartyId())) {
            throw new BusinessException(CustomerErrorCode.CUST_112);
        }
        if (partyRelationRepository
                .existsByFromPartyIdAndToPartyIdAndRelationTypeCodeAndRelationEndDateIsNullAndDeletedAtIsNull(
                        fromPartyId, req.toPartyId(), req.relationTypeCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_113);
        }

        PartyRelation relation = partyRelationRepository.save(PartyRelation.builder()
                .fromPartyId(fromPartyId)
                .toPartyId(req.toPartyId())
                .relationTypeCode(req.relationTypeCode())
                .relationDetailCode(req.relationDetailCode())
                .equityRatioBps(req.equityRatioBps())
                .representationScope(req.representationScope())
                .proofUrl(req.proofUrl())
                .relationStartDate(req.relationStartDate())
                .relationReviewStatusCode(PartyRelation.REVIEW_PENDING) // 신규 관계는 직원 검토 대기
                .build());
        return PartyRelationResponse.from(relation);
    }

    // ── 대리인 위임장 검토 (직원용) ────────────────────────────────────────────

    /** 대리인 위임장 검토 대기 목록(review_status='PENDING'). 대리인 검토 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.AgentReviewResponse>
            listPendingAgentReviews(org.springframework.data.domain.Pageable pageable) {
        return partyRelationRepository.searchPendingReviews(pageable);
    }

    @Transactional
    public PartyRelationResponse approveReview(Long relationId) {
        PartyRelation relation = partyRelationRepository.findById(relationId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_114));
        relation.approveReview();
        return PartyRelationResponse.from(relation);
    }

    @Transactional
    public PartyRelationResponse rejectReview(Long relationId) {
        PartyRelation relation = partyRelationRepository.findById(relationId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_114));
        relation.rejectReview();
        return PartyRelationResponse.from(relation);
    }

    @Transactional
    public PartyRelationResponse endRelation(Long relationId, String endDate, String reasonCode) {
        PartyRelation relation = partyRelationRepository.findById(relationId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_114));
        relation.end(endDate != null ? endDate : BusinessDate.today(), reasonCode);
        return PartyRelationResponse.from(relation);
    }

    // ── 컴플라이언스 조회/수정 (직원용) ────────────────────────────────────────

    /** EDD 심사 대기 목록(edd_required_yn='T'). EDD 심사·승인 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.EddPendingResponse>
            listEddPending(org.springframework.data.domain.Pageable pageable) {
        return complianceInfoRepository.searchEddPending(pageable);
    }

    /** 제재대상 스크리닝 목록(OFAC·UN·EU·KR 제재). 제재대상 Hit 검토 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.SanctionedPartyResponse>
            listSanctioned(org.springframework.data.domain.Pageable pageable) {
        return complianceInfoRepository.searchSanctioned(pageable);
    }

    /** FATCA/CRS 보고대상 목록. FATCA/CRS 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.FatcaReportableResponse>
            listFatcaCrsReportable(org.springframework.data.domain.Pageable pageable) {
        return complianceInfoRepository.searchFatcaCrsReportable(pageable);
    }

    /** KYC 만료 예정 목록. targetDate(YYYYMMDD) 이하 만료분을 만료 임박 순으로 반환한다. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.KycExpiringResponse>
            listKycExpiring(String targetDate, org.springframework.data.domain.Pageable pageable) {
        return complianceInfoRepository.searchKycExpiring(targetDate, pageable);
    }

    /** 미성년(만 19세 미만) 목록. 기준일=오늘-19년 이후 출생자. 미성년 검토 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.bank.customer.party.dto.MinorResponse>
            listMinors(org.springframework.data.domain.Pageable pageable) {
        String thresholdYmd = BusinessDate.todayDate().minusYears(19).format(DATE_FMT);
        return partyPersonRepository.searchMinors(thresholdYmd, pageable);
    }

    // ── 제재 스크리닝 Hit 검토 (직원용) ────────────────────────────────────────

    /** 제재 스크리닝 검토 대기 큐(status='PENDING'). 제재대상 Hit 검토 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SanctionHitResponse>
            listPendingScreeningHits(org.springframework.data.domain.Pageable pageable) {
        return sanctionScreeningHitRepository.searchPending(pageable);
    }

    /** Hit 무혐의(동명이인 등) 처리. */
    @Transactional
    public void clearScreeningHit(Long hitId, Long reviewerEmployeeId, String comment) {
        findHit(hitId).clearAsHomonym(reviewerEmployeeId, comment);
    }

    /** Hit 제재 확정 처리. */
    @Transactional
    public void confirmScreeningHit(Long hitId, Long reviewerEmployeeId, String comment) {
        findHit(hitId).confirmSanction(reviewerEmployeeId, comment);
    }

    private SanctionScreeningHit findHit(Long hitId) {
        return sanctionScreeningHitRepository.findById(hitId)
                .filter(h -> h.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_115));
    }

    // ── 중복고객 검토 (직원용) ─────────────────────────────────────────────────

    /** 중복고객 검토 대기 큐(status='PENDING'). 중복고객 검토 화면의 진입점. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<DuplicateReviewResponse>
            listPendingDuplicates(org.springframework.data.domain.Pageable pageable) {
        return duplicateReviewCaseRepository.searchPending(pageable);
    }

    /** 복본 확정 처리. */
    @Transactional
    public void markDuplicate(Long caseId, Long reviewerEmployeeId, String comment) {
        findDuplicateCase(caseId).markDuplicate(reviewerEmployeeId, comment);
    }

    /** 별개(동명이인 등) 확정 처리. */
    @Transactional
    public void markDistinct(Long caseId, Long reviewerEmployeeId, String comment) {
        findDuplicateCase(caseId).markDistinct(reviewerEmployeeId, comment);
    }

    private DuplicateReviewCase findDuplicateCase(Long caseId) {
        return duplicateReviewCaseRepository.findById(caseId)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));
    }

    @Transactional(readOnly = true)
    public ComplianceInfo getCompliance(Long partyId) {
        return complianceInfoRepository.findByPartyIdAndDeletedAtIsNull(partyId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_115));
    }

    @Transactional
    public void updateAmlRisk(Long partyId, String riskLevel, Long assessedByEmployeeId) {
        ComplianceInfo info = complianceInfoRepository.findByPartyIdAndDeletedAtIsNull(partyId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_115));
        info.updateAmlRisk(riskLevel, assessedByEmployeeId);
    }

    @Transactional
    public void completeKyc(Long partyId, String expiryDate, String methodCode, Long completedByEmployeeId) {
        ComplianceInfo info = complianceInfoRepository.findByPartyIdAndDeletedAtIsNull(partyId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_115));
        info.completeKyc(expiryDate, methodCode, completedByEmployeeId);
    }

    /** 고객 ID → partyId 변환 헬퍼 */
    public Long resolvePartyId(Long customerId) {
        return customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .map(Customer::getPartyId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));
    }
}
