package com.bank.customer.mobileauth.service;

import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.customer.crypto.CryptoService;
import com.bank.customer.identity.domain.IdentityVerification;
import com.bank.customer.identity.port.IdentityVerificationPort;
import com.bank.customer.identity.repository.IdentityVerificationRepository;
import com.bank.customer.mobileauth.domain.MobileAuth;
import com.bank.customer.mobileauth.dto.SendMobileAuthRequest;
import com.bank.customer.mobileauth.dto.VerifyMobileAuthRequest;
import com.bank.customer.mobileauth.dto.VerifyMobileAuthResponse;
import com.bank.customer.mobileauth.repository.MobileAuthRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class MobileAuthService {

    private static final int    OTP_EXPIRY_MINUTES = 3;
    private static final int    MAX_ATTEMPTS       = 5;
    /** local 프로파일 전용 고정 인증번호 — 실 SMS 게이트웨이가 없는 개발/시연 환경에서만 사용 */
    private static final String LOCAL_FIXED_CODE   = "000000";

    private final MobileAuthRepository           mobileAuthRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationPort       identityVerificationPort;
    private final CryptoService                  cryptoService;
    private final Environment                    environment;

    /**
     * 인증 코드 발송.
     * MVP: 실제 SMS 발송 없이 로그로만 출력. 운영 환경에서는 SmsGateway Bean으로 교체.
     */
    @Transactional
    public Long send(SendMobileAuthRequest req, String ip, Long customerId) {
        boolean local = isLocalProfile();
        String  code  = local ? LOCAL_FIXED_CODE : generateCode();

        log.info("[MobileAuth-{}] {} → {} (목적: {})",
                local ? "LOCAL" : "MOCK", req.phoneNumber(), code, req.purposeCode());

        MobileAuth auth = mobileAuthRepository.save(MobileAuth.builder()
                .customerId(customerId)
                .mobileAuthMethodTypeCode(req.methodTypeCode())
                .mobileAuthTelecomCarrierCode(req.telecomCarrierCode())
                .mobileAuthRecipientPhoneNumber(req.phoneNumber())
                .mobileAuthCodeHash(Sha256.hex(code))
                .mobileAuthPurposeCode(req.purposeCode())
                .mobileAuthRequestIp(ip)
                .mobileAuthRequestChannelCode(MobileAuth.CHANNEL_WEB)
                .mobileAuthSentAt(OffsetDateTime.now())
                .mobileAuthExpiryAt(OffsetDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES))
                .mobileAuthVerifiedYn(false)
                .mobileAuthAttemptCount(0)
                .build());

        return auth.getMobileAuthId();
    }

    /**
     * 인증 코드 검증.
     * 신원확인 목적(SIGNUP/IDENTITY_VERIFY)이면 주민번호 기반 본인확인까지 수행해
     * IdentityVerification 이력(실 CI·생년월일·성별 + RRN 암호문)을 저장하고 verificationId 를 돌려준다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public VerifyMobileAuthResponse verify(VerifyMobileAuthRequest req, Long customerId) {
        MobileAuth auth = mobileAuthRepository
                .findTopByMobileAuthRecipientPhoneNumberAndMobileAuthPurposeCodeAndMobileAuthVerifiedYnOrderByMobileAuthSentAtDesc(
                        req.phoneNumber(), req.purposeCode(), false)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_090));

        if (auth.isExpired()) {
            throw new BusinessException(CustomerErrorCode.CUST_091);
        }
        if (auth.isVerified()) {
            throw new BusinessException(CustomerErrorCode.CUST_093);
        }
        if (auth.getMobileAuthAttemptCount() >= MAX_ATTEMPTS) {
            throw new BusinessException(CustomerErrorCode.CUST_091);
        }

        auth.recordAttempt();

        if (!Sha256.hex(req.code()).equals(auth.getMobileAuthCodeHash())) {
            auth.fail("WRONG_CODE");
            throw new BusinessException(CustomerErrorCode.CUST_092);
        }

        auth.verify();

        // 주민번호가 제공된 경우에만 신원확인 이력 생성(가입용). 미제공(단순 전화인증)은 verificationId 없이 통과.
        if (req.rrn() == null || req.rrn().isBlank()) {
            return new VerifyMobileAuthResponse(null);
        }

        // 신원확인: 주민번호로 CI·생년월일·성별 파생(본인확인기관 목) + RRN 암호화 저장(평문 미보관).
        IdentityVerificationPort.VerifiedIdentity vi =
                identityVerificationPort.resolve(req.name(), req.rrn(), req.phoneNumber());

        IdentityVerification saved = identityVerificationRepository.save(IdentityVerification.builder()
                .customerId(customerId)
                .mobileAuthId(auth.getMobileAuthId())
                .identityVerificationAgencyCode(IdentityVerification.AGENCY_NICE)
                .identityVerificationPurposeCode(req.purposeCode())
                .identityVerificationCiValue(vi.ci())
                .identityVerificationName(req.name())
                .identityVerificationBirthDate(vi.birthDate())
                .identityVerificationGenderCode(vi.genderCode())
                .identityVerificationNationalityTypeCode(vi.nationalityTypeCode())
                .identityVerificationTelecomCarrierCode(auth.getMobileAuthTelecomCarrierCode())
                .identityVerificationPhoneNumber(req.phoneNumber())
                .identityVerifiedAt(OffsetDateTime.now())
                .rrnEncrypted(cryptoService.encrypt(req.rrn()))
                .build());

        return new VerifyMobileAuthResponse(saved.getIdentityVerificationId());
    }

    private boolean isLocalProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    private static String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

}
