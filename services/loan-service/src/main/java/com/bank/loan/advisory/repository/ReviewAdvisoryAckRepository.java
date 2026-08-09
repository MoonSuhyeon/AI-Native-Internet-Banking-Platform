package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReviewAdvisoryAckRepository extends JpaRepository<ReviewAdvisoryAck, Long> {

    List<ReviewAdvisoryAck> findByAdvrIdOrderByAckedAtAsc(Long advrId);

    List<ReviewAdvisoryAck> findByAdvrIdInOrderByAckedAtAsc(Collection<Long> advrIds);
}
