package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TransactionCertificate;
import com.bank.deposit.domain.enums.CertificateType;
import com.bank.deposit.dto.CertificateResponse;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.TransactionCertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 거래 증빙 — 거래영수증·이체확인증.
 *
 * <p>화면에 버튼은 있었지만 부를 곳이 없었다. 레포 전체에서 증빙 관련 코드가 0건이라
 * "거래영수증"·"이체확인증 건별/일괄 출력" 셋이 눌러도 아무 일이 없었다.
 *
 * <p>발급 대상은 게이트웨이가 주입한 고객번호로 정한다 — 요청 본문에서 받지 않는다.
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionCertificateController {

    private final TransactionCertificateService certificateService;

    /** 단건 발급 — 거래영수증·이체확인증 건별. */
    @PostMapping("/{transactionId}/certificates")
    public CertificateResponse issue(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER,
                    required = false) String authenticatedCustomerId,
            @PathVariable Long transactionId,
            @RequestParam CertificateType type) {
        return certificateService.issue(authenticatedCustomerId, transactionId, type);
    }

    /** 일괄 발급 — 이체확인증 일괄 출력. 한 건이라도 안 되면 전부 실패한다. */
    @PostMapping("/certificates/batch")
    public List<CertificateResponse> issueBatch(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER,
                    required = false) String authenticatedCustomerId,
            @RequestParam CertificateType type,
            @RequestBody List<Long> transactionIds) {
        return certificateService.issueBatch(authenticatedCustomerId, transactionIds, type);
    }

    /**
     * 증빙 번호로 대조.
     *
     * <p>신원을 요구하지 않는다 — 증빙을 제출받은 쪽(거래 상대·기관)이 진위를 확인하는
     * 경로이기 때문이다. 대신 번호가 추측 불가능한 난수라야 이 공개가 안전하다
     * (`CertificateNumberGenerator` 참조).
     */
    @GetMapping("/certificates/{certificateNo}")
    public CertificateResponse verify(@PathVariable String certificateNo) {
        return certificateService.findByNo(certificateNo);
    }

    /** 한 거래의 발급 이력. 재발급이 몇 번 있었는지 본다. */
    @GetMapping("/{transactionId}/certificates")
    public List<TransactionCertificate> history(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER,
                    required = false) String authenticatedCustomerId,
            @PathVariable Long transactionId) {
        return certificateService.history(authenticatedCustomerId, transactionId);
    }
}
