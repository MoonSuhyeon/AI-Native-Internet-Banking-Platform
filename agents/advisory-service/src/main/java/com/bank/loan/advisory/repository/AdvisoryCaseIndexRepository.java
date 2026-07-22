package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.AdvisoryCaseIndex;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvisoryCaseIndexRepository extends JpaRepository<AdvisoryCaseIndex, Long> {

    boolean existsByRevId(Long revId);

    int countByEmbeddingModelCd(String embeddingModelCd);
}
