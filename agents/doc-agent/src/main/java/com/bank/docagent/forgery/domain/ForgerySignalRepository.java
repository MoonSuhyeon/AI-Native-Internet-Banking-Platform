package com.bank.docagent.forgery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ForgerySignalRepository extends JpaRepository<ForgerySignalEntity, Long> {

    List<ForgerySignalEntity> findBySubmissionId(UUID submissionId);
}
