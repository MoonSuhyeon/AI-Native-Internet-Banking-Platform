package com.bank.customer.mobileauth.repository;

import com.bank.customer.mobileauth.domain.MobileAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MobileAuthRepository extends JpaRepository<MobileAuth, Long> {

    /** 미검증 최신 인증 요청 조회 — 전화번호 + 목적 기준 */
    Optional<MobileAuth> findTopByMobileAuthRecipientPhoneNumberAndMobileAuthPurposeCodeAndMobileAuthVerifiedYnOrderByMobileAuthSentAtDesc(
            String phoneNumber, String purposeCode, Boolean verifiedYn);
}
