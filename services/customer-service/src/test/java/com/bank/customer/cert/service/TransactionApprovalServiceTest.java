package com.bank.customer.cert.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.AuthToken;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.dto.TransactionApprovalIssueRequest;
import com.bank.customer.cert.dto.TransactionApprovalIssueResponse;
import com.bank.customer.cert.dto.TransactionApprovalVerifyRequest;
import com.bank.customer.cert.repository.AuthTokenRepository;
import com.bank.customer.support.CustomerErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 자금이동 step-up — 승인 토큰이 거래에 묶이는가, 한 번만 쓰이는가.
 *
 * <p>토큰이 거래에 묶이지 않으면 1,000원 이체로 받은 토큰으로 1,000만원을 보낼 수 있다.
 * 이 파일에서 가장 중요한 것은 그 바인딩을 지키는 테스트다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransactionApprovalService")
class TransactionApprovalServiceTest {

    @Mock CertPinVerifier certPinVerifier;
    @Mock AuthTokenRepository authTokenRepository;

    private TransactionApprovalService service;

    /** 저장된 토큰을 해시로 되찾을 수 있는 가짜 저장소. */
    private final Map<String, AuthToken> stored = new HashMap<>();

    private static final Long CUSTOMER_ID = 1L;
    private static final String SERIAL = "CERT-SERIAL-001";
    private static final String FROM_ACCOUNT = "001-001-000001";
    private static final String TO_ACCOUNT = "111-222-333";
    private static final BigDecimal AMOUNT = new BigDecimal("100000");

    @BeforeEach
    void setUp() {
        service = new TransactionApprovalService(certPinVerifier, authTokenRepository);

        Certificate cert = mock(Certificate.class);
        given(cert.getCustomerId()).willReturn(CUSTOMER_ID);
        given(certPinVerifier.verify(anyString(), anyString(), anyString(), anyString())).willReturn(cert);

        given(authTokenRepository.save(any())).willAnswer(inv -> {
            AuthToken token = inv.getArgument(0);
            stored.put(token.getAuthTokenHash(), token);
            return token;
        });
        given(authTokenRepository.findByAuthTokenHash(anyString()))
                .willAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0, String.class))));
    }

    private TransactionApprovalIssueResponse issue() {
        return service.issue(CUSTOMER_ID,
                new TransactionApprovalIssueRequest(FROM_ACCOUNT, TO_ACCOUNT, AMOUNT, SERIAL, "123456"),
                "127.0.0.1");
    }

    private TransactionApprovalVerifyRequest verifyRequest(String token, String from, String to, BigDecimal amount) {
        return new TransactionApprovalVerifyRequest(token, from, to, amount);
    }

    @Test
    @DisplayName("발급된 토큰으로 같은 거래를 검증하면 통과한다")
    void verifyMatchingTransaction() {
        String token = issue().approvalToken();

        assertThatCode(() -> service.verify(verifyRequest(token, FROM_ACCOUNT, TO_ACCOUNT, AMOUNT)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토큰 원문은 저장하지 않는다")
    void tokenIsStoredAsHashOnly() {
        String token = issue().approvalToken();

        assertThat(stored.keySet()).noneMatch(hash -> hash.contains(token));
    }

    @Test
    @DisplayName("금액이 다르면 같은 토큰이어도 거부한다 — 바인딩의 핵심")
    void rejectsDifferentAmount() {
        String token = issue().approvalToken();

        assertThatThrownBy(() -> service.verify(
                verifyRequest(token, FROM_ACCOUNT, TO_ACCOUNT, new BigDecimal("10000000"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_145));
    }

    @Test
    @DisplayName("수취 계좌가 다르면 거부한다")
    void rejectsDifferentPayee() {
        String token = issue().approvalToken();

        assertThatThrownBy(() -> service.verify(
                verifyRequest(token, FROM_ACCOUNT, "999-888-777", AMOUNT)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("출금 계좌가 다르면 거부한다")
    void rejectsDifferentSourceAccount() {
        String token = issue().approvalToken();

        assertThatThrownBy(() -> service.verify(
                verifyRequest(token, "999-999-999999", TO_ACCOUNT, AMOUNT)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 토큰을 두 번 쓸 수 없다 — 1회용")
    void rejectsReuse() {
        String token = issue().approvalToken();
        service.verify(verifyRequest(token, FROM_ACCOUNT, TO_ACCOUNT, AMOUNT));

        assertThatThrownBy(() -> service.verify(verifyRequest(token, FROM_ACCOUNT, TO_ACCOUNT, AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_145));
    }

    @Test
    @DisplayName("유효시간이 지난 토큰은 거부하고 만료로 표시한다")
    void rejectsExpired() {
        String token = issue().approvalToken();
        AuthToken saved = stored.values().iterator().next();
        // 발급 시각을 과거로 돌리는 대신, 만료 판정에 쓰이는 값을 직접 지난 시각으로 둔다.
        AuthToken expired = AuthToken.builder()
                .customerId(saved.getCustomerId())
                .authTokenHash(saved.getAuthTokenHash())
                .authMethodTypeCode(saved.getAuthMethodTypeCode())
                .authTokenPurposeCode(saved.getAuthTokenPurposeCode())
                .authTokenIssuedAt(saved.getAuthTokenIssuedAt().minusMinutes(10))
                .authTokenExpiryAt(saved.getAuthTokenIssuedAt().minusMinutes(7))
                .build();
        stored.put(saved.getAuthTokenHash(), expired);

        assertThatThrownBy(() -> service.verify(verifyRequest(token, FROM_ACCOUNT, TO_ACCOUNT, AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_146));
        assertThat(expired.getAuthTokenStatusCode()).isEqualTo(AuthToken.STATUS_EXPIRED);
    }

    @Test
    @DisplayName("남의 인증서로는 승인 토큰을 받을 수 없다")
    void rejectsOtherCustomersCertificate() {
        Certificate otherCert = mock(Certificate.class);
        given(otherCert.getCustomerId()).willReturn(999L);
        given(certPinVerifier.verify(anyString(), anyString(), anyString(), anyString())).willReturn(otherCert);

        assertThatThrownBy(this::issue)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_030));
    }

    @Test
    @DisplayName("PIN 이 틀리면 토큰을 발급하지 않는다")
    void doesNotIssueWhenPinFails() {
        given(certPinVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .willThrow(new BusinessException(CustomerErrorCode.CUST_033));

        assertThatThrownBy(this::issue).isInstanceOf(BusinessException.class);
        assertThat(stored).isEmpty();
    }
}
