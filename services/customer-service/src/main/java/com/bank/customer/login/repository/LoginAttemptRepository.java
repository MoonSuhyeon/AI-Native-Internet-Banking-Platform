package com.bank.customer.login.repository;

import com.bank.customer.login.domain.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** 특정 고객의 window 내 로그인 실패 횟수 — FDS LOGIN_FAILURE_COUNT 룰용 */
    @Query("""
            SELECT COUNT(la)
            FROM LoginAttempt la
            WHERE la.customerId = :customerId
              AND la.loginAttemptSuccessYn = false
              AND la.loginAttemptedAt >= :since
            """)
    long countFailuresSince(@Param("customerId") Long customerId,
                            @Param("since") OffsetDateTime since);
}
