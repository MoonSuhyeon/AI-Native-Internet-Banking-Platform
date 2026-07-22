package com.bank.docagent.verify.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanProductDocumentRepository
    extends JpaRepository<LoanProductDocument, LoanProductDocument.LoanProductDocumentId> {

    List<LoanProductDocument> findByProductId(String productId);

    List<LoanProductDocument> findByProductIdAndEssentialTrue(String productId);

    java.util.Optional<LoanProductDocument> findByProductIdAndReqDocCode(String productId, String reqDocCode);
}
