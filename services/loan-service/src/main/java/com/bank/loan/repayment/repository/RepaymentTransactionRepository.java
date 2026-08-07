package com.bank.loan.repayment.repository;

import com.bank.loan.repayment.domain.RepaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface RepaymentTransactionRepository extends JpaRepository<RepaymentTransaction, Long> {

    Optional<RepaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<RepaymentTransaction> findByPiId(String piId);

    Optional<RepaymentTransaction> findByRtxIdAndDeletedAtIsNull(Long rtxId);

    List<RepaymentTransaction> findByCntrIdAndDeletedAtIsNullOrderByPaidAtAsc(Long cntrId);

    /**
     * 해당 거래(rtxId) 를 가리키는 SUCCESS 상태의 역분개 row 가 있는지.
     * 중복 역분개 차단에 사용.
     */
    @Query("""
            select case when count(t) > 0 then true else false end
              from RepaymentTransaction t
             where t.reversalTargetRtxId = :targetRtxId
               and t.reversalYn = true
               and t.rtxStatusCd = 'SUCCESS'
               and t.deletedAt is null
            """)
    boolean existsActiveReversal(@Param("targetRtxId") Long targetRtxId);

    @Query("""
            select coalesce(sum(t.interestAmount), 0)
              from RepaymentTransaction t
             where t.cntrId = :cntrId
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
            """)
    long sumInterestAmount(@Param("cntrId") Long cntrId);

    /**
     * 중도상환(TYPE_EARLY) 으로 갚은 원금 누계. outstanding 잔액 계산에 사용.
     * SUCCESS 이고 reversal_yn='N' 인 row 만 합산.
     */
    @Query("""
            select coalesce(sum(t.principalAmount), 0)
              from RepaymentTransaction t
             where t.cntrId = :cntrId
               and t.rtxTypeCd = 'EARLY'
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
            """)
    long sumEarlyPrincipal(@Param("cntrId") Long cntrId);

    /**
     * 회차당 누적 납부합 — SCHEDULED + PARTIAL SUCCESS 이고 reversal_yn='N' 인 row 의 total_amount 합.
     * 부분상환 잔액 검증과 PAID 자동 전이에 사용.
     */
    @Query("""
            select coalesce(sum(t.totalAmount), 0)
              from RepaymentTransaction t
             where t.rschId = :rschId
               and t.rtxTypeCd in ('SCHEDULED','PARTIAL')
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
            """)
    long sumPaidByRschId(@Param("rschId") Long rschId);

    /**
     * 회차당 누적 이자 합 — 같은 조건의 interest_amount 합.
     * 부분상환 분배 순서 정석(이자 먼저 → 원금) 적용 시 남은 이자 계산에 사용.
     */
    @Query("""
            select coalesce(sum(t.interestAmount), 0)
              from RepaymentTransaction t
             where t.rschId = :rschId
               and t.rtxTypeCd in ('SCHEDULED','PARTIAL')
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
            """)
    long sumPaidInterestByRschId(@Param("rschId") Long rschId);

    /**
     * 회차당 누적 연체이자 합 — 같은 조건의 overdue_interest_amount 합.
     * 분배 순서 정석 1단계(연체이자 → ...) 적용 시 남은 연체이자 계산에 사용.
     */
    @Query("""
            select coalesce(sum(t.overdueInterestAmount), 0)
              from RepaymentTransaction t
             where t.rschId = :rschId
               and t.rtxTypeCd in ('SCHEDULED','PARTIAL')
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
            """)
    long sumPaidOverdueInterestByRschId(@Param("rschId") Long rschId);

    /**
     * 지정 EARLY 거래 이후 같은 계약에 또 다른 활성 EARLY 가 있는지 확인.
     * EARLY 역분개는 "최신 EARLY 만" 지원하므로, 본 메서드가 true 면 차단해야 한다.
     */
    @Query("""
            select case when count(t) > 0 then true else false end
              from RepaymentTransaction t
             where t.cntrId = :cntrId
               and t.rtxTypeCd = 'EARLY'
               and t.rtxStatusCd = 'SUCCESS'
               and t.reversalYn = false
               and t.deletedAt is null
               and t.rtxId <> :targetRtxId
               and t.paidAt > :targetPaidAt
            """)
    boolean existsLaterEarly(@Param("cntrId") Long cntrId,
                             @Param("targetRtxId") Long targetRtxId,
                             @Param("targetPaidAt") java.time.OffsetDateTime targetPaidAt);

    /** common_db 동기화 후 브리지 컬럼 백필. */
    @Modifying
    @Transactional
    @Query("UPDATE RepaymentTransaction t SET t.transactionId = :commonId WHERE t.rtxId = :rtxId")
    int updateTransactionId(@Param("rtxId") Long rtxId, @Param("commonId") Long commonId);
}
