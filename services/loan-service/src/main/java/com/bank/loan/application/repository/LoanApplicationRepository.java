package com.bank.loan.application.repository;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.applicationexpiry.dto.ExpiryCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    Optional<LoanApplication> findByIdempotencyKey(String idempotencyKey);

    Optional<LoanApplication> findByApplIdAndDeletedAtIsNull(Long applId);

    /** doc-agent 이벤트는 내부 PK 가 아니라 신청번호(applNo)로 대상을 지목한다. */
    Optional<LoanApplication> findByApplNoAndDeletedAtIsNull(String applNo);

    List<LoanApplication> findByCustomerIdAndDeletedAtIsNullOrderByApplIdDesc(Long customerId);

    /**
     * 상품별 applicationValidityDays 를 함께 조회. coalesce(p.applicationValidityDays, 14) 로
     * 상품 설정이 없으면 시스템 기본 14일 적용.
     * 필터링(threshold 비교)은 서비스 레이어에서 per-row 수행.
     */
    @Query("""
            select new com.bank.loan.applicationexpiry.dto.ExpiryCandidate(
                a.applId,
                r.approvedAt,
                coalesce(p.applicationValidityDays, 14)
            )
              from LoanApplication a
              join LoanReview r    on r.applId = a.applId
              left join LoanProduct p on p.prodId = a.prodId and p.deletedAt is null
             where a.applStatusCd = 'APPROVED'
               and a.deletedAt is null
               and r.deletedAt is null
               and r.approvedAt is not null
             order by a.applId asc
            """)
    List<ExpiryCandidate> findApprovedWithValidityDays();
}
