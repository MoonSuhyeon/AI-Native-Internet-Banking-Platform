package com.bank.docagent.submission.repository;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentSubmissionRepository extends JpaRepository<DocumentSubmission, UUID> {

    List<DocumentSubmission> findByApplicationId(String applicationId);

    List<DocumentSubmission> findByHumanReviewStatus(HumanReviewStatus status, Sort sort);
}
