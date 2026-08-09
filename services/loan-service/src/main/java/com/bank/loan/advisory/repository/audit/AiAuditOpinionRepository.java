package com.bank.loan.advisory.repository.audit;

import com.bank.loan.advisory.domain.audit.AiAuditOpinion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAuditOpinionRepository extends JpaRepository<AiAuditOpinion, Long> {

    List<AiAuditOpinion> findByAdvrId(Long advrId);

    List<AiAuditOpinion> findByReviewerIdOrderByGeneratedAtDesc(Long reviewerId);

    List<AiAuditOpinion> findAllByOrderByGeneratedAtDesc(Pageable pageable);
}
