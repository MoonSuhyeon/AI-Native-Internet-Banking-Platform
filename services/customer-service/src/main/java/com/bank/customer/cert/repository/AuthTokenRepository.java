package com.bank.customer.cert.repository;

import com.bank.customer.cert.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /**
     * 해시로 찾는다. 해시에 거래 내용이 섞여 있으므로, 같은 토큰이라도 거래 내용이 다르면
     * 조회되지 않는다 — 그것이 바인딩이다.
     */
    Optional<AuthToken> findByAuthTokenHash(String authTokenHash);
}
