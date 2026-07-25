package com.bank.customer.cert.service;

import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.dto.CertLoginRequest;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.service.FdsService;
import com.bank.customer.history.domain.CertificateUse;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.service.AuthEventService;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertLoginService {

    private final CertificateRepository     certificateRepository;
    private final CredentialRepository      credentialRepository;
    private final CustomerRepository        customerRepository;
    private final CertificateUseRepository  certificateUseRepository;
    private final PasswordEncoder           passwordEncoder;
    private final FdsService                fdsService;
    private final AuthEventService          authEventService;

    /**
     * 인증서 로그인.
     * PIN 검증은 MVP 단계에서 certificate.certPinHash(없으면 credential.passwordHash)로 위임한다.
     *
     * <p>인증수단 고유 이력 {@code certificate_use} 와 {@code CERT_LOGIN} FDS 는 이 서비스가 직접 남기고,
     * 공통 후처리(로그인 시도 이력·토큰·세션·{@code LOGIN_ATTEMPT} FDS)는 {@link AuthEventService} 에 위임한다.
     * attempt 의 식별자에는 로그인ID가 없으므로 인증서 일련번호를 기록한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse certLogin(CertLoginRequest request, String ip, String userAgent) {

        Certificate cert = null;
        try {
            cert = certificateRepository
                    .findByCertificateSerialNumberAndDeletedAtIsNull(request.certSerialNumber())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_030));

            if (cert.isLocked()) {
                throw new BusinessException(CustomerErrorCode.CUST_034);
            }
            if (!cert.isActive()) {
                if (Certificate.STATUS_REVOKED.equals(cert.getCertificateStatusCode())) {
                    throw new BusinessException(CustomerErrorCode.CUST_032);
                }
                throw new BusinessException(CustomerErrorCode.CUST_031);
            }
            if (cert.isExpired()) {
                throw new BusinessException(CustomerErrorCode.CUST_031);
            }

            boolean pinValid;
            if (cert.getCertPinHash() != null) {
                pinValid = passwordEncoder.matches(request.pin(), cert.getCertPinHash());
            } else {
                var credential = credentialRepository
                        .findByCustomerIdAndDeletedAtIsNull(cert.getCustomerId())
                        .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_010));
                pinValid = passwordEncoder.matches(request.pin(), credential.getPasswordHash());
            }

            if (!pinValid) {
                cert.recordLoginFailure();
                String resultCode = cert.isLocked()
                        ? CertificateUse.RESULT_FAIL_LOCKED
                        : CertificateUse.RESULT_FAIL_PIN;
                CertificateUse use = saveCertUse(cert, ip, resultCode,
                        cert.isLocked() ? CustomerErrorCode.CUST_034.getCode()
                                        : CustomerErrorCode.CUST_033.getCode());
                // 인증서 실패 누적 FDS 평가 — BLOCK 룰(CERT_FAIL_BLOCK_5) 발동 시 CUST_060 으로 차단
                fdsService.evaluate(cert.getCustomerId(), FdsDetection.EVENT_CERT_LOGIN, use.getCertificateUseId());
                throw new BusinessException(
                        cert.isLocked() ? CustomerErrorCode.CUST_034 : CustomerErrorCode.CUST_033);
            }

            Customer customer = customerRepository
                    .findByCustomerIdAndDeletedAtIsNull(cert.getCustomerId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

            if (!customer.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }

            cert.recordLoginSuccess();
            saveCertUse(cert, ip, CertificateUse.RESULT_SUCCESS, null);

            // 공통 후처리: 로그인 시도 이력 + 토큰 발급 + 세션 + LOGIN_ATTEMPT FDS(silent)
            return authEventService.onLoginSuccess(
                    customer, request.certSerialNumber(), ip, userAgent, AuthEventService.CHANNEL_WEB);

        } catch (BusinessException e) {
            // 인증서 고유 실패 이력(만료·폐기 등). PIN 실패는 위에서 이미 저장했으므로 resolveFailResultCode 가 null 반환.
            if (cert != null && cert.getCertificateStatusCode() != null) {
                String resultCode = resolveFailResultCode(e);
                if (resultCode != null) {
                    saveCertUse(cert, ip, resultCode, e.getErrorCode().getCode());
                }
            }
            // 공통 로그인 시도 이력. 인증서 고유 FDS(CERT_LOGIN)는 위에서 평가하므로 LOGIN_ATTEMPT 는 중복 평가하지 않는다.
            Long customerId = (cert != null) ? cert.getCustomerId() : null;
            authEventService.onLoginFailure(request.certSerialNumber(), customerId, ip, userAgent,
                    AuthEventService.CHANNEL_WEB, e.getErrorCode().getCode(), false);
            throw e;
        }
    }

    private CertificateUse saveCertUse(Certificate cert, String ip, String resultCode, String failureReason) {
        // MVP: signedDataHash = serial + ip + timestamp 해시, signatureValue = certPinHash 또는 "N/A"
        String signedData = Sha256.hex(cert.getCertificateSerialNumber() + ip + OffsetDateTime.now());
        String sigValue   = cert.getCertPinHash() != null ? cert.getCertPinHash() : "N/A";

        return certificateUseRepository.save(CertificateUse.builder()
                .certificateId(cert.getCertificateId())
                .customerId(cert.getCustomerId())
                .purposeCode(CertificateUse.PURPOSE_LOGIN)
                .signedDataHash(signedData)
                .signatureValue(sigValue)
                .verificationResultCode(resultCode)
                .failureReasonCode(failureReason)
                .requestIp(ip)
                .requestChannelCode(CertificateUse.CHANNEL_WEB)
                .usedAt(OffsetDateTime.now())
                .build());
    }

    /** BusinessException 코드에서 certificate_use 결과 코드 매핑. 이미 saveCertUse를 호출한 경우 null 반환. */
    private String resolveFailResultCode(BusinessException e) {
        String code = e.getErrorCode().getCode();
        return switch (code) {
            case "CUST_031" -> CertificateUse.RESULT_FAIL_EXPIRED;
            case "CUST_032" -> CertificateUse.RESULT_FAIL_REVOKED;
            case "CUST_034" -> null; // PIN 실패 경로에서 이미 저장
            default         -> null;
        };
    }

}
