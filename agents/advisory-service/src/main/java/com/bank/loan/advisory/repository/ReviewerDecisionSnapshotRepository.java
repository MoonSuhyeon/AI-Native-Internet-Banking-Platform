package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewerDecisionSnapshotRepository extends JpaRepository<ReviewerDecisionSnapshot, Long> {

    List<ReviewerDecisionSnapshot> findByReviewerIdAndSnapshotDateOrderByCohortDimensionCdAscCohortValueAsc(
            Long reviewerId, String snapshotDate);

    List<ReviewerDecisionSnapshot> findBySnapshotDateAndCohortDimensionCdOrderByReviewerIdAsc(
            String snapshotDate, String cohortDimensionCd);

    List<ReviewerDecisionSnapshot> findBySnapshotDateOrderByReviewerIdAsc(String snapshotDate);

    List<ReviewerDecisionSnapshot> findByCohortDimensionCdAndCohortValueOrderBySnapshotDateDesc(
            String cohortDimensionCd, String cohortValue);
}
