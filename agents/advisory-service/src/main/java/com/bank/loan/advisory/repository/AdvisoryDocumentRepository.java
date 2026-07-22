package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.AdvisoryDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdvisoryDocumentRepository extends JpaRepository<AdvisoryDocument, Long> {

    Optional<AdvisoryDocument> findByDocIdAndDeletedAtIsNull(Long docId);

    Optional<AdvisoryDocument> findByDocCdAndDocVersionAndDeletedAtIsNull(String docCd, String docVersion);

    List<AdvisoryDocument> findByActiveYnAndDeletedAtIsNull(String activeYn);

    List<AdvisoryDocument> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    long countByDeletedAtIsNull();

    long countByActiveYnAndDeletedAtIsNull(String activeYn);
}
