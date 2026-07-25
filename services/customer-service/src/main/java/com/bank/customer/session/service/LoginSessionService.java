package com.bank.customer.session.service;

import com.bank.common.security.Sha256;
import com.bank.customer.session.domain.ApiToken;
import com.bank.customer.session.domain.LoginSession;
import com.bank.customer.session.repository.ApiTokenRepository;
import com.bank.customer.session.repository.LoginSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginSessionService {

    private final LoginSessionRepository loginSessionRepository;
    private final ApiTokenRepository     apiTokenRepository;

    /**
     * 로그인 성공 시 세션 + 토큰 이력 저장.
     * 순환 FK(login_session.token_id ↔ api_token.session_id)는 DEFERRABLE INITIALLY DEFERRED 로
     * 같은 트랜잭션 내에서 처리한다:
     *   1) tokenId=0 더미로 session INSERT
     *   2) api_token INSERT (session_id 참조 가능)
     *   3) session.token_id UPDATE
     *
     * @param accessTokenRaw  원문 JWT access token
     * @param refreshTokenRaw 원문 JWT refresh token
     * @param accessExpiry    access token 만료 시각
     * @param refreshExpiry   refresh token 만료 시각
     */
    @Transactional
    public String createSession(Long customerId, Long loginAttemptId, String ip,
                                String accessTokenRaw, String refreshTokenRaw,
                                OffsetDateTime accessExpiry, OffsetDateTime refreshExpiry) {

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        // 1) 더미 tokenId=0 으로 session 먼저 저장 (DEFERRABLE FK라 COMMIT 전까지 검증 안 함)
        LoginSession session = loginSessionRepository.save(LoginSession.builder()
                .sessionId(sessionId)
                .customerId(customerId)
                .loginAttemptId(loginAttemptId)
                .tokenId(0L)
                .sessionIssuedIp(ip)
                .sessionChannelCode(LoginSession.CHANNEL_WEB)
                .sessionStatusCode(LoginSession.STATUS_ACTIVE)
                .sessionMfaCompletedYn("F")
                .sessionExpiryAt(refreshExpiry)
                .build());

        // 2) ACCESS 토큰 저장
        ApiToken accessToken = apiTokenRepository.save(ApiToken.builder()
                .customerId(customerId)
                .sessionId(sessionId)
                .tokenTypeCode(ApiToken.TYPE_ACCESS)
                .tokenHash(Sha256.hex(accessTokenRaw))
                .tokenIssuedChannelCode(ApiToken.CHANNEL_WEB)
                .tokenIssuedAt(OffsetDateTime.now())
                .tokenExpiryAt(accessExpiry)
                .build());

        // 3) REFRESH 토큰 저장
        apiTokenRepository.save(ApiToken.builder()
                .customerId(customerId)
                .sessionId(sessionId)
                .tokenTypeCode(ApiToken.TYPE_REFRESH)
                .tokenHash(Sha256.hex(refreshTokenRaw))
                .tokenIssuedChannelCode(ApiToken.CHANNEL_WEB)
                .tokenIssuedAt(OffsetDateTime.now())
                .tokenExpiryAt(refreshExpiry)
                .build());

        // 4) session.token_id 를 ACCESS 토큰 ID로 업데이트
        session.linkToken(accessToken.getTokenId());

        return sessionId;
    }

    /** 로그아웃 시 세션 종료 + 토큰 폐기 */
    @Transactional
    public void endSession(String sessionId, Long customerId) {
        loginSessionRepository.findBySessionIdAndCustomerId(sessionId, customerId)
                .ifPresent(s -> s.end("LOGOUT"));

        apiTokenRepository.findBySessionIdAndTokenRevokedAtIsNull(sessionId)
                .forEach(t -> t.revoke("LOGOUT"));
    }

}
