package com.bank.customer.cert.service;

import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.service.FdsService;
import com.bank.customer.history.domain.CertificateUse;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 인증서 상태·PIN 검증. 로그인과 거래 승인이 함께 쓴다.
 *
 * <p><b>왜 뽑았나.</b> 이 판정 묶음 — 인증서 조회, 잠금·폐기·만료 검사, PIN 대조,
 * 실패 시 카운트 증가·사용이력 저장·FDS 평가 — 은 원래 {@code CertLoginService.certLogin}
 * 안에만 있었다. 그래서 인증서로 확인할 수 있는 것은 "로그인" 하나뿐이었고,
 * 이체 같은 거래를 승인시키려면 같은 로직을 복사해야 했다. 복사하면 잠금 정책이나
 * FDS 연동이 한쪽에만 반영되는 일이 생긴다.
 *
 * <p>검증 결과를 무엇으로 바꿔 내보낼지(로그인 토큰 / 거래 승인 토큰)는 호출자가 정한다.
 * 여기서는 "이 인증서와 PIN 이 맞는가"까지만 책임진다.
 *
 * <p>사용 이력({@code certificate_use})의 용도 코드는 호출자가 넘긴다 — 로그인인지
 * 거래 승인인지가 사후 조사에서 구별돼야 한다.
 */
@Component
@RequiredArgsConstructor
public class CertPinVerifier {

    private final CertificateRepository certificateRepository;
    private final CredentialRepository credentialRepository;
    private final CertificateUseRepository certificateUseRepository;
    private final FdsService fdsService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 인증서를 찾아 상태와 PIN 을 검증한다.
     *
     * @param serialNumber 인증서 일련번호
     * @param pin          입력 PIN
     * @param ip           요청 IP (사용 이력 기록용)
     * @param purposeCode  {@link CertificateUse} 의 용도 코드 (LOGIN / 거래 승인)
     * @return 검증된 인증서
     * @throws BusinessException CUST_030(없음)·031(만료)·032(폐기)·033(PIN 불일치)·034(잠금)
     */
    public Certificate verify(String serialNumber, String pin, String ip, String purposeCode) {
        Certificate cert = certificateRepository
                .findByCertificateSerialNumberAndDeletedAtIsNull(serialNumber)
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

        if (!pinMatches(cert, pin)) {
            cert.recordLoginFailure();
            String resultCode = cert.isLocked()
                    ? CertificateUse.RESULT_FAIL_LOCKED
                    : CertificateUse.RESULT_FAIL_PIN;
            CertificateUse use = saveCertUse(cert, ip, purposeCode, resultCode,
                    cert.isLocked() ? CustomerErrorCode.CUST_034.getCode()
                                    : CustomerErrorCode.CUST_033.getCode());
            // 인증서 실패 누적 FDS 평가 — BLOCK 룰(CERT_FAIL_BLOCK_5) 발동 시 CUST_060 으로 차단
            fdsService.evaluate(cert.getCustomerId(), FdsDetection.EVENT_CERT_LOGIN, use.getCertificateUseId());
            throw new BusinessException(
                    cert.isLocked() ? CustomerErrorCode.CUST_034 : CustomerErrorCode.CUST_033);
        }
        return cert;
    }

    /** PIN 대조. 인증서 자체 PIN 해시가 없으면 로그인 비밀번호 해시로 위임한다(MVP 단계 유지). */
    private boolean pinMatches(Certificate cert, String pin) {
        if (cert.getCertPinHash() != null) {
            return passwordEncoder.matches(pin, cert.getCertPinHash());
        }
        var credential = credentialRepository
                .findByCustomerIdAndDeletedAtIsNull(cert.getCustomerId())
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_010));
        return passwordEncoder.matches(pin, credential.getPasswordHash());
    }

    /** 인증서 사용 이력 저장. 성공·실패 양쪽에서 쓴다. */
    public CertificateUse saveCertUse(Certificate cert, String ip, String purposeCode,
                                      String resultCode, String failureReason) {
        // MVP: signedDataHash = serial + ip + timestamp 해시, signatureValue = certPinHash 또는 "N/A"
        String signedData = Sha256.hex(cert.getCertificateSerialNumber() + ip + OffsetDateTime.now());
        String sigValue   = cert.getCertPinHash() != null ? cert.getCertPinHash() : "N/A";

        return certificateUseRepository.save(CertificateUse.builder()
                .certificateId(cert.getCertificateId())
                .customerId(cert.getCustomerId())
                .purposeCode(purposeCode)
                .signedDataHash(signedData)
                .signatureValue(sigValue)
                .verificationResultCode(resultCode)
                .failureReasonCode(failureReason)
                .requestIp(ip)
                .requestChannelCode(CertificateUse.CHANNEL_WEB)
                .usedAt(OffsetDateTime.now())
                .build());
    }
}
