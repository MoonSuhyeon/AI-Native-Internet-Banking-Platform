package com.bank.customer.cert.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.AuthMethod;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.dto.CertIssueRequest;
import com.bank.customer.cert.dto.CertIssueResponse;
import com.bank.customer.cert.repository.AuthMethodRepository;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CertIssueService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int CERT_VALIDITY_YEARS = 3;

    private final CredentialRepository credentialRepository;
    private final AuthMethodRepository authMethodRepository;
    private final CertificateRepository certificateRepository;
    private final PasswordEncoder passwordEncoder;

    @SuppressWarnings("null")
    public CertIssueResponse issue(CertIssueRequest request) {
        // 1. 자격증명 확인
        var credential = credentialRepository
                .findByLoginIdAndDeletedAtIsNull(request.loginId())
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_010));

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            throw new BusinessException(CustomerErrorCode.CUST_010);
        }

        long customerId = credential.getCustomerId();
        LocalDate today  = BusinessDate.todayDate();
        LocalDate expiry = today.plusYears(CERT_VALIDITY_YEARS);
        String todayStr  = today.format(DATE_FMT);
        String expiryStr = expiry.format(DATE_FMT);

        // 2. 기존 동일 타입 인증서 폐기
        certificateRepository.revokeAllActive(customerId, request.certType());

        // 3. auth_method 생성
        AuthMethod authMethod = authMethodRepository.save(AuthMethod.builder()
                .customerId(customerId)
                .authMethodTypeCode(request.certType())
                .authMethodAliasName(switch (request.certType()) {
                        case "CERT_FIN"   -> "금융인증서";
                        case "CERT_AXFUL" -> "AXful인증서";
                        default           -> "공동인증서";
                    })
                .authMethodStatusCode(AuthMethod.STATUS_ACTIVE)
                .primaryAuthMethodYn(false)
                .authMethodRegisteredDate(todayStr)
                .authMethodExpiryDate(expiryStr)
                .build());

        // 3. 시리얼번호 생성: {TYPE}-{CUSTOMER_ID}-{DATE}-{UUID_SHORT}
        String serial = String.format("%s-%d-%s-%s",
                request.certType().replace("CERT_", ""),
                customerId,
                todayStr,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());

        String issuerName  = "AXful Bank CA";
        String subjectDn   = String.format("CN=%d, OU=개인, O=AXful Bank, C=KR", customerId);
        String issuerDn    = "CN=AXful Bank CA, O=AXful Bank, C=KR";
        String publicKey   = "MOCK_PUBLIC_KEY_" + serial;
        String purposeCode = "FINANCIAL";

        String certPinHash = passwordEncoder.encode(request.certPin());

        // 4. certificate 생성
        certificateRepository.save(Certificate.builder()
                .customerId(customerId)
                .authMethodId(authMethod.getAuthMethodId())
                .certificateTypeCode(request.certType())
                .certificateSerialNumber(serial)
                .certificateIssuerName(issuerName)
                .certificateSubjectDn(subjectDn)
                .certificateIssuerDn(issuerDn)
                .certificatePublicKey(publicKey)
                .certificatePurposeCode(purposeCode)
                .certificateIssuedDate(todayStr)
                .certificateExpiryDate(expiryStr)
                .certificateStatusCode(Certificate.STATUS_ACTIVE)
                .certPinHash(certPinHash)
                .certLoginFailureCount(0)
                .maxCertLoginFailureCount(5)
                .build());

        return new CertIssueResponse(serial, request.certType(), issuerName, subjectDn, todayStr, expiryStr);
    }
}
