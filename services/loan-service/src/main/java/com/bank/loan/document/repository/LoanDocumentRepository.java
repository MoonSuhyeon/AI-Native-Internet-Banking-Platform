package com.bank.loan.document.repository;

import com.bank.loan.document.domain.LoanDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, Long> {

    Optional<LoanDocument> findByDocIdAndDeletedAtIsNull(Long docId);

    List<LoanDocument> findByApplIdAndDeletedAtIsNullOrderBySubmittedAtAsc(Long applId);

    Optional<LoanDocument> findByDocUrlAndDeletedAtIsNull(String docUrl);

    /**
     * 보존기한이 지났고 아직 원본을 파기하지 않은 서류.
     *
     * <p>soft delete 된 서류도 대상에 포함한다 — 화면에서 지웠다고 원본이 사라지는 게 아니라
     * 보존기한까지는 남겨두는 것이 규정이고, 기한이 지나면 똑같이 파기 대상이다.
     * retention_until 은 yyyyMMdd 문자열이라 사전순 비교가 곧 날짜순 비교다.
     */
    @Query("""
            select d
              from LoanDocument d
             where d.retentionUntil is not null
               and d.retentionUntil < :today
               and d.purgedAt is null
             order by d.docId asc
            """)
    List<LoanDocument> findPurgeTargets(@Param("today") String today, Pageable pageable);
}
