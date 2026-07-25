package com.bank.customer.cert.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.dto.CertDetailResponse;
import com.bank.customer.cert.dto.CertSummaryResponse;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertManageService {

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "CERT_FIN",    "금융인증서",
            "CERT_COMMON", "공동인증서",
            "CERT_AXFUL",  "AXful인증서"
    );
    private static final Map<String, String> STATUS_NAMES = Map.of(
            "ACTIVE",    "정상",
            "REVOKED",   "폐기",
            "EXPIRED",   "만료",
            "SUSPENDED", "정지"
    );

    private final CertificateRepository certificateRepository;

    public List<CertSummaryResponse> listCerts(Long customerId) {
        return certificateRepository.findByCustomerIdAndDeletedAtIsNull(customerId).stream()
                .map(c -> new CertSummaryResponse(
                        c.getCertificateSerialNumber(),
                        c.getCertificateTypeCode(),
                        TYPE_NAMES.getOrDefault(c.getCertificateTypeCode(), c.getCertificateTypeCode()),
                        c.getCertificateIssuerName(),
                        c.getCertificateIssuedDate(),
                        c.getCertificateExpiryDate(),
                        c.getCertificateStatusCode(),
                        STATUS_NAMES.getOrDefault(c.getCertificateStatusCode(), c.getCertificateStatusCode())
                ))
                .toList();
    }

    public CertDetailResponse getCertDetail(Long customerId, String serialNumber) {
        Certificate c = findOwned(customerId, serialNumber);
        return new CertDetailResponse(
                c.getCertificateSerialNumber(),
                c.getCertificateTypeCode(),
                TYPE_NAMES.getOrDefault(c.getCertificateTypeCode(), c.getCertificateTypeCode()),
                c.getCertificateIssuerName(),
                c.getCertificateSubjectDn(),
                c.getCertificateIssuerDn(),
                c.getCertificateIssuedDate(),
                c.getCertificateExpiryDate(),
                c.getCertificateStatusCode(),
                STATUS_NAMES.getOrDefault(c.getCertificateStatusCode(), c.getCertificateStatusCode()),
                c.getCertificatePurposeCode(),
                c.getCertPinHash() != null
        );
    }

    @Transactional
    public void revoke(Long customerId, String serialNumber) {
        Certificate c = findOwned(customerId, serialNumber);
        if (Certificate.STATUS_REVOKED.equals(c.getCertificateStatusCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_032);
        }
        c.revoke("USER_REQUEST");
    }

    private Certificate findOwned(Long customerId, String serialNumber) {
        Certificate c = certificateRepository
                .findByCertificateSerialNumberAndDeletedAtIsNull(serialNumber)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_030));
        if (!c.getCustomerId().equals(customerId)) {
            throw new BusinessException(CustomerErrorCode.CUST_030);
        }
        return c;
    }
}
