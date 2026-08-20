package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.entity.TransactionCertificate;
import com.bank.deposit.domain.enums.CertificateType;
import com.bank.deposit.dto.CertificateResponse;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.TransactionCertificateRepository;
import com.bank.deposit.repository.TransactionRepository;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 거래 증빙 발급.
 *
 * <p><b>발급 대상은 요청이 아니라 신원에서 온다.</b> 요청 본문에 고객번호를 넣게 두면
 * 남의 번호를 적을 수 있고, 그러면 "누구에게 발급했는가" 가 자칭이 된다.
 *
 * <p>그래서 여기서는 게이트웨이가 주입한 고객번호가 <b>없으면 발급을 막는다</b>.
 * {@link AuthenticatedCustomerValidator#validateTransactionOwner}는 헤더가 없을 때
 * 통과시키는데(기존 동작), 조회는 그래도 되지만 발급은 대상을 몰라 성립하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionCertificateService {

    /** 한 번에 발급할 수 있는 최대 건수. 일괄 발급이 무제한이면 대량 조회 수단이 된다. */
    static final int MAX_BATCH = 50;

    private final TransactionRepository transactionRepository;
    private final TransactionCertificateRepository certificateRepository;
    private final AuthenticatedCustomerValidator customerValidator;
    private final CertificateNumberGenerator numberGenerator;
    private final Clock clock;

    @Transactional
    public CertificateResponse issue(
            String authenticatedCustomerId, Long transactionId, CertificateType type) {

        String issuedTo = requireIdentity(authenticatedCustomerId);
        customerValidator.validateTransactionOwner(authenticatedCustomerId, transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));

        TransactionCertificate certificate;
        try {
            certificate = TransactionCertificate.issue(
                    transaction, type, issuedTo,
                    numberGenerator.generate(type), OffsetDateTime.now(clock));
        } catch (IllegalStateException e) {
            // 실패한 거래·이체가 아닌 거래 — 요청이 잘못된 것이지 서버 오류가 아니다.
            throw new BusinessException(ErrorCode.INVALID_STATUS, e.getMessage());
        }

        TransactionCertificate saved = certificateRepository.save(certificate);
        // 증빙 발급은 개인정보 접근이다. 누가 무엇을 언제 뽑았는지 남긴다.
        log.info("증빙 발급: certificateNo={} transactionId={} type={} issuedTo={}",
                saved.getCertificateNo(), transactionId, type, issuedTo);
        return CertificateResponse.of(saved, transaction);
    }

    /**
     * 일괄 발급 — 이체 결과조회의 "이체확인증 일괄 출력".
     *
     * <p>한 건이라도 발급할 수 없으면 <b>전부 실패시킨다</b>. 일부만 발급되면 화면에는
     * 몇 장이 나왔는지 알 수 없고, 사용자는 빠진 건을 모른 채 제출하게 된다.
     */
    @Transactional
    public List<CertificateResponse> issueBatch(
            String authenticatedCustomerId, List<Long> transactionIds, CertificateType type) {

        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "발급할 거래를 지정해야 한다");
        }
        if (transactionIds.size() > MAX_BATCH) {
            throw new BusinessException(ErrorCode.INVALID_STATUS,
                    "한 번에 " + MAX_BATCH + "건까지 발급할 수 있다: 요청=" + transactionIds.size());
        }
        return transactionIds.stream()
                .distinct()
                .map(id -> issue(authenticatedCustomerId, id, type))
                .toList();
    }

    /** 증빙 번호로 대조. 발급받은 쪽이 진위를 확인하는 경로다. */
    @Transactional(readOnly = true)
    public CertificateResponse findByNo(String certificateNo) {
        TransactionCertificate cert = certificateRepository.findByCertificateNo(certificateNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "증빙을 찾을 수 없다: " + certificateNo));
        Transaction tx = transactionRepository.findById(cert.getTransactionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
        return CertificateResponse.of(cert, tx);
    }

    /** 한 거래의 발급 이력. 재발급이 몇 번 있었는지 본다. */
    @Transactional(readOnly = true)
    public List<TransactionCertificate> history(String authenticatedCustomerId, Long transactionId) {
        customerValidator.validateTransactionOwner(authenticatedCustomerId, transactionId);
        return certificateRepository.findByTransactionIdOrderByIssuedAtDesc(transactionId);
    }

    private String requireIdentity(String authenticatedCustomerId) {
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "게이트웨이를 거친 요청만 증빙을 발급할 수 있다 — 발급 대상을 알 수 없다");
        }
        return authenticatedCustomerId.trim();
    }
}
