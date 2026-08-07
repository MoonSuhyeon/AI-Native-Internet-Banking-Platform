package com.bank.customer.cert.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.domain.QrLoginToken;
import com.bank.customer.cert.dto.QrCertApproveRequest;
import com.bank.customer.cert.dto.QrCertStatusResponse;
import com.bank.customer.cert.dto.QrGenerateResponse;
import com.bank.customer.cert.repository.AuthMethodRepository;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.cert.repository.QrLoginTokenRepository;
import com.bank.customer.cert.domain.AuthMethod;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Transactional
public class QrCertService {

    private static final String QR_CERT_PREFIX  = "QR_CERT:";
    private static final Duration QR_TOKEN_TTL  = Duration.ofMinutes(3);
    private static final Duration QR_CERT_TTL   = Duration.ofMinutes(5);
    private static final int CERT_VALIDITY_YEARS = 3;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final QrLoginTokenRepository qrLoginTokenRepository;
    private final CredentialRepository credentialRepository;
    private final AuthMethodRepository authMethodRepository;
    private final CertificateRepository certificateRepository;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    public QrGenerateResponse generate(String requestIp) {
        String tokenHash   = UUID.randomUUID().toString().replace("-", "");
        String confirmCode = String.valueOf((int)(Math.random() * 9000) + 1000);
        OffsetDateTime expiry = OffsetDateTime.now().plus(QR_TOKEN_TTL);

        qrLoginTokenRepository.save(QrLoginToken.builder()
                .qrTokenHash(tokenHash)
                .qrStatusCode(QrLoginToken.STATUS_PENDING)
                .requestIp(requestIp)
                .requestChannelCode("WEB_CERT")
                .issuedAt(OffsetDateTime.now())
                .expiryAt(expiry)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());

        redisTemplate.opsForValue().set(QR_CERT_PREFIX + tokenHash + ":code", confirmCode, QR_TOKEN_TTL);

        return new QrGenerateResponse(tokenHash, confirmCode, expiry);
    }

    @Transactional
    public QrCertStatusResponse getStatus(String tokenHash) {
        QrLoginToken token = qrLoginTokenRepository.findByQrTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_040));

        if (token.isExpiredByTime() && QrLoginToken.STATUS_PENDING.equals(token.getQrStatusCode())) {
            token.expire();
        }

        if (QrLoginToken.STATUS_APPROVED.equals(token.getQrStatusCode())) {
            String serial    = redisTemplate.opsForValue().get(QR_CERT_PREFIX + tokenHash + ":serial");
            String issued    = redisTemplate.opsForValue().get(QR_CERT_PREFIX + tokenHash + ":issued");
            String expiry    = redisTemplate.opsForValue().get(QR_CERT_PREFIX + tokenHash + ":expiry");
            return new QrCertStatusResponse(token.getQrStatusCode(), serial, issued, expiry);
        }

        return new QrCertStatusResponse(token.getQrStatusCode(), null, null, null);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void approve(QrCertApproveRequest request) {
        QrLoginToken token = qrLoginTokenRepository.findByQrTokenHash(request.tokenHash())
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_040));

        if (token.isExpiredByTime()) { token.expire(); throw new BusinessException(CustomerErrorCode.CUST_041); }
        if (!QrLoginToken.STATUS_PENDING.equals(token.getQrStatusCode())
                && !QrLoginToken.STATUS_SCANNED.equals(token.getQrStatusCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_042);
        }

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

        certificateRepository.revokeAllActive(customerId, "CERT_AXFUL");

        AuthMethod authMethod = authMethodRepository.save(AuthMethod.builder()
                .customerId(customerId)
                .authMethodTypeCode("CERT_AXFUL")
                .authMethodAliasName("AXful인증서")
                .authMethodStatusCode(AuthMethod.STATUS_ACTIVE)
                .primaryAuthMethodYn(false)
                .authMethodRegisteredDate(todayStr)
                .authMethodExpiryDate(expiryStr)
                .build());

        String serial = String.format("AXFUL-%d-%s-%s",
                customerId, todayStr,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());

        certificateRepository.save(Certificate.builder()
                .customerId(customerId)
                .authMethodId(authMethod.getAuthMethodId())
                .certificateTypeCode("CERT_AXFUL")
                .certificateSerialNumber(serial)
                .certificateIssuerName("AXful Bank CA")
                .certificateSubjectDn(String.format("CN=%d, OU=개인, O=AXful Bank, C=KR", customerId))
                .certificateIssuerDn("CN=AXful Bank CA, O=AXful Bank, C=KR")
                .certificatePublicKey("MOCK_PUBLIC_KEY_" + serial)
                .certificatePurposeCode("FINANCIAL")
                .certificateIssuedDate(todayStr)
                .certificateExpiryDate(expiryStr)
                .certificateStatusCode(Certificate.STATUS_ACTIVE)
                .certLoginFailureCount(0)
                .maxCertLoginFailureCount(5)
                .build());

        redisTemplate.opsForValue().set(QR_CERT_PREFIX + request.tokenHash() + ":serial", serial,   QR_CERT_TTL);
        redisTemplate.opsForValue().set(QR_CERT_PREFIX + request.tokenHash() + ":issued", todayStr, QR_CERT_TTL);
        redisTemplate.opsForValue().set(QR_CERT_PREFIX + request.tokenHash() + ":expiry", expiryStr, QR_CERT_TTL);

        token.approve(customerId);
    }
}
