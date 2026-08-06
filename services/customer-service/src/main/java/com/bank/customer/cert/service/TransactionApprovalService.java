package com.bank.customer.cert.service;

import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.AuthToken;
import com.bank.customer.cert.domain.Certificate;
import com.bank.customer.cert.dto.TransactionApprovalIssueRequest;
import com.bank.customer.cert.dto.TransactionApprovalIssueResponse;
import com.bank.customer.cert.dto.TransactionApprovalVerifyRequest;
import com.bank.customer.cert.repository.AuthTokenRepository;
import com.bank.customer.history.domain.CertificateUse;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 거래 승인 토큰 발급·검증.
 *
 * <p><b>왜 필요한가.</b> 로그인은 "누구인가"를 묻고 이체는 "지금 이 금액을 이 계좌로 보내는
 * 것이 맞는가"를 묻는다. 세션이 탈취됐을 때 마지막으로 남는 방어선이 후자라, 로그인 인증만으로
 * 이체가 되면 세션 하나가 곧 자금 유출이다.
 *
 * <p><b>왜 계정계가 아니라 여기인가.</b> 인증수단(인증서·PIN)의 비밀은 이 도메인에 있다.
 * 계정계가 PIN 을 직접 받아 대조하게 하면 비밀이 두 도메인에 퍼진다. 계정계는 토큰 한 장만
 * 확인하면 되게 한다.
 *
 * <p>설계 배경과 도입 단계: docs/plan/transfer-step-up-auth.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionApprovalService {

    /** 승인 후 이체까지 주어지는 시간. 길면 탈취 창이 커지고, 짧으면 사용자가 못 끝낸다. */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(3);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertPinVerifier certPinVerifier;
    private final AuthTokenRepository authTokenRepository;

    /**
     * 인증서 PIN 을 확인하고 이 거래에 한정된 승인 토큰을 발급한다.
     *
     * @param customerId 게이트웨이가 넘긴 인증 고객 ID
     */
    @Transactional
    public TransactionApprovalIssueResponse issue(Long customerId,
                                                  TransactionApprovalIssueRequest request,
                                                  String ip) {
        Certificate cert = certPinVerifier.verify(
                request.certSerialNumber(), request.pin(), ip, CertificateUse.PURPOSE_TRANSFER_APPROVAL);

        // 남의 인증서로 내 거래를 승인시키는 경로를 막는다.
        if (!cert.getCustomerId().equals(customerId)) {
            throw new BusinessException(CustomerErrorCode.CUST_030);
        }

        certPinVerifier.saveCertUse(cert, ip, CertificateUse.PURPOSE_TRANSFER_APPROVAL,
                CertificateUse.RESULT_SUCCESS, null);

        String token = newToken();
        OffsetDateTime now = OffsetDateTime.now();
        authTokenRepository.save(AuthToken.builder()
                .customerId(customerId)
                .authTokenHash(bind(token, request.fromAccountNo(), request.toAccountNo(), request.amount()))
                .authMethodTypeCode(AuthToken.METHOD_CERT)
                .authTokenPurposeCode(AuthToken.PURPOSE_TRANSFER)
                .authTokenIssuedAt(now)
                .authTokenExpiryAt(now.plus(TOKEN_TTL))
                .build());

        return new TransactionApprovalIssueResponse(token, now.plus(TOKEN_TTL));
    }

    /**
     * 계정계가 이체 직전에 부른다. 성공하면 토큰을 즉시 소멸시킨다(1회용).
     *
     * <p>거래 내용이 발급 때와 다르면 해시가 달라 조회 자체가 안 된다 — 그것이 바인딩이다.
     */
    @Transactional
    public void verify(TransactionApprovalVerifyRequest request) {
        String hash = bind(request.approvalToken(), request.fromAccountNo(),
                request.toAccountNo(), request.amount());

        // 조회 실패는 토큰이 틀렸거나 거래 내용이 발급 때와 다르다는 뜻이다. 둘을 구별해
        // 알려주지 않는다 — 어느 쪽인지 알려주면 금액을 바꿔가며 떠보는 데 쓰인다.
        AuthToken token = authTokenRepository.findByAuthTokenHash(hash)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_145));

        OffsetDateTime now = OffsetDateTime.now();
        if (!token.isActive()) {
            // 이미 쓴 토큰의 재사용 — 사후 조사에서 드러나야 하므로 남긴다.
            log.warn("거래 승인 토큰 재사용 시도 customerId={} status={}",
                    token.getCustomerId(), token.getAuthTokenStatusCode());
            throw new BusinessException(CustomerErrorCode.CUST_145);
        }
        if (token.isExpired(now)) {
            token.markExpired();
            throw new BusinessException(CustomerErrorCode.CUST_146);
        }
        token.markUsed(now);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 토큰과 거래 내용을 함께 해시한다.
     *
     * <p>토큰만 해시하면 "이 사람이 인증했다"까지만 뜻하게 되고, 1,000원 이체로 받은 토큰으로
     * 1,000만원을 보낼 수 있다. 거래 내용을 섞어야 "이 사람이 <b>이 이체를</b> 승인했다"가 된다.
     */
    private String bind(String token, String fromAccountNo, String toAccountNo, BigDecimal amount) {
        return Sha256.hex(String.join("|",
                token,
                fromAccountNo,
                toAccountNo,
                amount.stripTrailingZeros().toPlainString()));
    }
}
