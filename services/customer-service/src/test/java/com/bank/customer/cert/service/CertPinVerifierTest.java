package com.bank.customer.cert.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.repository.CertificateRepository;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.service.FdsService;
import com.bank.customer.history.domain.CertificateUse;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.support.CustomerErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 인증서 PIN 검증의 FDS 연동.
 *
 * <p>핵심: 실패 경로에서 fdsService.evaluate(CERT_LOGIN)가 실제로 호출되어
 * CERT_FAIL_BLOCK 룰이 동작(CUST_060 차단)하는지 확인한다.
 *
 * <p>원래 CertLoginServiceTest 에 있던 테스트다. 검증 로직이 CertPinVerifier 로
 * 옮겨가면서(거래 승인도 같은 검증을 쓰기 위해) 테스트도 함께 옮겼다 —
 * 로그인·거래 승인 양쪽이 이 검증에 기대므로 여기서 지키는 편이 맞다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertPinVerifierTest {

    @Mock CertificateRepository       certificateRepository;
    @Mock CredentialRepository        credentialRepository;
    @Mock CertificateUseRepository    certificateUseRepository;
    @Mock PasswordEncoder             passwordEncoder;
    @Mock FdsService                  fdsService;

    private CertPinVerifier certPinVerifier;

    private static final String SERIAL = "CERT-SERIAL-001";

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 필드 순서대로 직접 주입
        certPinVerifier = new CertPinVerifier(
                certificateRepository, credentialRepository, certificateUseRepository,
                fdsService, passwordEncoder);

        // saveCertUse 가 referenceId 로 사용하는 id 보장
        CertificateUse savedUse = mock(CertificateUse.class);
        given(savedUse.getCertificateUseId()).willReturn(500L);
        given(certificateUseRepository.save(any())).willReturn(savedUse);
    }

    private Certificate mockActiveCert() {
        Certificate cert = mock(Certificate.class);
        given(cert.isLocked()).willReturn(false);
        given(cert.isActive()).willReturn(true);
        given(cert.isExpired()).willReturn(false);
        given(cert.getCertPinHash()).willReturn("$hashed-pin");
        given(cert.getCustomerId()).willReturn(1L);
        given(cert.getCertificateId()).willReturn(10L);
        given(cert.getCertificateSerialNumber()).willReturn(SERIAL);
        given(cert.getCertificateStatusCode()).willReturn("ISSUED");
        return cert;
    }

    @Test
    @DisplayName("인증서 PIN 실패 시 FDS 평가(CERT_LOGIN)를 실제로 호출한다 — 죽은 코드 방지")
    void certLoginFailure_invokesFds() {
        Certificate cert = mockActiveCert();
        given(certificateRepository.findByCertificateSerialNumberAndDeletedAtIsNull(SERIAL))
                .willReturn(Optional.of(cert));
        given(passwordEncoder.matches(eq("wrong-pin"), any())).willReturn(false);

        // BLOCK 미발동(evaluate no-op) → 기본 PIN 실패 코드(CUST_033)
        assertThatThrownBy(() -> certPinVerifier.verify(SERIAL, "wrong-pin", "127.0.0.1", CertificateUse.PURPOSE_LOGIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_033));

        // 핵심 검증: 실패 경로에서 FDS 평가가 호출됨 (이전엔 호출부 부재로 룰이 죽은 코드였음)
        verify(fdsService).evaluate(eq(1L), eq(FdsDetection.EVENT_CERT_LOGIN), anyLong());
    }

    @Test
    @DisplayName("FDS BLOCK 룰 발동 시 CUST_060 으로 차단된다 (CERT_FAIL_BLOCK_5)")
    void certLoginFailure_fdsBlock_throwsCust060() {
        Certificate cert = mockActiveCert();
        given(certificateRepository.findByCertificateSerialNumberAndDeletedAtIsNull(SERIAL))
                .willReturn(Optional.of(cert));
        given(passwordEncoder.matches(eq("wrong-pin"), any())).willReturn(false);

        // 누적 실패가 임계치에 도달해 BLOCK 룰 발동 → evaluate 가 CUST_060 을 던진다
        doThrow(new BusinessException(CustomerErrorCode.CUST_060))
                .when(fdsService).evaluate(anyLong(), eq(FdsDetection.EVENT_CERT_LOGIN), anyLong());

        assertThatThrownBy(() -> certPinVerifier.verify(SERIAL, "wrong-pin", "127.0.0.1", CertificateUse.PURPOSE_LOGIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_060));
    }
}
