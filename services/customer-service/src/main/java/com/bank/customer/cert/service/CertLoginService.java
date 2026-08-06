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

    private final CertPinVerifier           certPinVerifier;
    private final CustomerRepository        customerRepository;
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
            // 인증서 상태·PIN 검증은 CertPinVerifier 가 한다. 거래 승인도 같은 검증을 쓴다 —
            // 복사해 두면 잠금 정책이나 FDS 연동이 한쪽에만 반영되는 일이 생긴다.
            cert = certPinVerifier.verify(request.certSerialNumber(), request.pin(), ip,
                    CertificateUse.PURPOSE_LOGIN);

            Customer customer = customerRepository
                    .findByCustomerIdAndDeletedAtIsNull(cert.getCustomerId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

            if (!customer.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }

            cert.recordLoginSuccess();
            certPinVerifier.saveCertUse(cert, ip, CertificateUse.PURPOSE_LOGIN, CertificateUse.RESULT_SUCCESS, null);

            // 공통 후처리: 로그인 시도 이력 + 토큰 발급 + 세션 + LOGIN_ATTEMPT FDS(silent)
            return authEventService.onLoginSuccess(
                    customer, request.certSerialNumber(), ip, userAgent, AuthEventService.CHANNEL_WEB);

        } catch (BusinessException e) {
            // 인증서 고유 실패 이력(만료·폐기 등). PIN 실패는 위에서 이미 저장했으므로 resolveFailResultCode 가 null 반환.
            if (cert != null && cert.getCertificateStatusCode() != null) {
                String resultCode = resolveFailResultCode(e);
                if (resultCode != null) {
                    certPinVerifier.saveCertUse(cert, ip, CertificateUse.PURPOSE_LOGIN, resultCode, e.getErrorCode().getCode());
                }
            }
            // 공통 로그인 시도 이력. 인증서 고유 FDS(CERT_LOGIN)는 위에서 평가하므로 LOGIN_ATTEMPT 는 중복 평가하지 않는다.
            Long customerId = (cert != null) ? cert.getCustomerId() : null;
            authEventService.onLoginFailure(request.certSerialNumber(), customerId, ip, userAgent,
                    AuthEventService.CHANNEL_WEB, e.getErrorCode().getCode(), false);
            throw e;
        }
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
