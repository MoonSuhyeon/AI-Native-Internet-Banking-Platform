package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.TransactionCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionCertificateRepository
        extends JpaRepository<TransactionCertificate, Long> {

    /** 증빙 번호로 대조. 발급받은 쪽이 진위를 확인하는 경로다. */
    Optional<TransactionCertificate> findByCertificateNo(String certificateNo);

    /** 한 거래의 발급 이력 — 재발급이 몇 번 있었는지 본다. */
    List<TransactionCertificate> findByTransactionIdOrderByIssuedAtDesc(Long transactionId);

    boolean existsByCertificateNo(String certificateNo);
}
