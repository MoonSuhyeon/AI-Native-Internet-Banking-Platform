package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewAdvisorySignalRepository extends JpaRepository<ReviewAdvisorySignal, Long> {

    List<ReviewAdvisorySignal> findByAdvrIdOrderByObservedAtAsc(Long advrId);
}
