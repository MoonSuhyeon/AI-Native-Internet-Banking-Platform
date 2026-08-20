package com.bank.customer.branch.repository;

import com.bank.customer.branch.domain.BranchConsultationReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchConsultationReservationRepository
        extends JpaRepository<BranchConsultationReservation, Long> {

    List<BranchConsultationReservation> findByCustomerIdOrderByReservedAtDesc(Long customerId);

    Optional<BranchConsultationReservation> findByReservationIdAndCustomerId(
            Long reservationId, Long customerId);
}
