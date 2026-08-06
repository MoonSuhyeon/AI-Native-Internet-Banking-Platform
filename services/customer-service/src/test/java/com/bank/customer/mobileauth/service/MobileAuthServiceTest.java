package com.bank.customer.mobileauth.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.crypto.CryptoService;
import com.bank.customer.identity.domain.IdentityVerification;
import com.bank.customer.identity.port.IdentityVerificationPort;
import com.bank.customer.identity.port.IdentityVerificationPort.VerifiedIdentity;
import com.bank.customer.identity.repository.IdentityVerificationRepository;
import com.bank.customer.mobileauth.domain.MobileAuth;
import com.bank.customer.mobileauth.dto.VerifyMobileAuthRequest;
import com.bank.customer.mobileauth.dto.VerifyMobileAuthResponse;
import com.bank.customer.mobileauth.repository.MobileAuthRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileAuthServiceTest {

    @Mock private MobileAuthRepository           mobileAuthRepository;
    @Mock private IdentityVerificationRepository identityVerificationRepository;
    @Mock private IdentityVerificationPort       identityVerificationPort;
    @Mock private Environment                     environment;

    private final CryptoService cryptoService = new CryptoService("unit-test-key", new org.springframework.mock.env.MockEnvironment());

    private MobileAuthService service() {
        return new MobileAuthService(mobileAuthRepository, identityVerificationRepository,
                identityVerificationPort, cryptoService, environment);
    }

    private MobileAuth pendingAuth(String code) {
        return MobileAuth.builder()
                .mobileAuthId(1L)
                .mobileAuthMethodTypeCode("SMS")
                .mobileAuthTelecomCarrierCode("SKT")
                .mobileAuthRecipientPhoneNumber("01012345678")
                .mobileAuthCodeHash(sha256(code))
                .mobileAuthPurposeCode("SIGNUP")
                .mobileAuthRequestIp("127.0.0.1")
                .mobileAuthRequestChannelCode(MobileAuth.CHANNEL_WEB)
                .mobileAuthSentAt(OffsetDateTime.now())
                .mobileAuthExpiryAt(OffsetDateTime.now().plusMinutes(3))
                .mobileAuthVerifiedYn("F")
                .mobileAuthAttemptCount(0)
                .build();
    }

    @Test
    @DisplayName("주민번호 제공 시 — 본인확인 이력(CI·RRN 암호문) 저장 후 verificationId 반환")
    void verifyWithRrn_createsIdentityAndReturnsId() {
        when(mobileAuthRepository
                .findTopByMobileAuthRecipientPhoneNumberAndMobileAuthPurposeCodeAndMobileAuthVerifiedYnOrderByMobileAuthSentAtDesc(
                        anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(pendingAuth("123456")));
        when(identityVerificationPort.resolve(anyString(), anyString(), anyString()))
                .thenReturn(new VerifiedIdentity("CI-XYZ", "19900101", "M", "DOMESTIC"));
        when(identityVerificationRepository.save(any())).thenAnswer(inv -> {
            IdentityVerification iv = inv.getArgument(0);
            return IdentityVerification.builder()
                    .identityVerificationId(77L)
                    .identityVerificationCiValue(iv.getIdentityVerificationCiValue())
                    .rrnEncrypted(iv.getRrnEncrypted())
                    .build();
        });

        VerifyMobileAuthRequest req = new VerifyMobileAuthRequest(
                "01012345678", "SIGNUP", "123456", "홍길동", "9001011234567");

        VerifyMobileAuthResponse res = service().verify(req, null);

        assertThat(res.verificationId()).isEqualTo(77L);

        ArgumentCaptor<IdentityVerification> captor = ArgumentCaptor.forClass(IdentityVerification.class);
        verify(identityVerificationRepository).save(captor.capture());
        IdentityVerification saved = captor.getValue();
        assertThat(saved.getIdentityVerificationCiValue()).isEqualTo("CI-XYZ");
        assertThat(saved.getRrnEncrypted()).isNotBlank();
        // 주민번호 평문이 그대로 저장되지 않는다(암호화)
        assertThat(saved.getRrnEncrypted()).isNotEqualTo("9001011234567");
    }

    @Test
    @DisplayName("주민번호 미제공 시 — 본인확인 이력 없이 verificationId null")
    void verifyWithoutRrn_returnsNull() {
        when(mobileAuthRepository
                .findTopByMobileAuthRecipientPhoneNumberAndMobileAuthPurposeCodeAndMobileAuthVerifiedYnOrderByMobileAuthSentAtDesc(
                        anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(pendingAuth("123456")));

        VerifyMobileAuthRequest req = new VerifyMobileAuthRequest(
                "01012345678", "PASSWORD_RESET", "123456", null, null);

        VerifyMobileAuthResponse res = service().verify(req, 5L);

        assertThat(res.verificationId()).isNull();
        verify(identityVerificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("인증번호 불일치 — CUST_092, 본인확인 이력 미생성")
    void verifyWrongCode_throws() {
        when(mobileAuthRepository
                .findTopByMobileAuthRecipientPhoneNumberAndMobileAuthPurposeCodeAndMobileAuthVerifiedYnOrderByMobileAuthSentAtDesc(
                        anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(pendingAuth("123456")));

        VerifyMobileAuthRequest req = new VerifyMobileAuthRequest(
                "01012345678", "SIGNUP", "000000", "홍길동", "9001011234567");

        assertThatThrownBy(() -> service().verify(req, null))
                .isInstanceOf(BusinessException.class);
        verify(identityVerificationRepository, never()).save(any());
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
