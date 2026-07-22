package com.bank.loan.advisory.repository.audit;

import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewerRiskScoreRepository extends JpaRepository<ReviewerRiskScore, Long> {

    Optional<ReviewerRiskScore> findByReviewerId(Long reviewerId);

    List<ReviewerRiskScore> findAllByOrderByBiasScoreDesc(Pageable pageable);

    List<ReviewerRiskScore> findAllByOrderByComplianceScoreDesc(Pageable pageable);
}
