package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.AdvisoryRetrievalLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdvisoryRetrievalLogRepository extends JpaRepository<AdvisoryRetrievalLog, Long> {

    List<AdvisoryRetrievalLog> findByAdvrIdOrderByRequestedAtAsc(Long advrId);
}
