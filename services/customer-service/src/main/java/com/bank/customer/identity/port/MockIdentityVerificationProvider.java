package com.bank.customer.identity.port;

import com.bank.common.web.BusinessException;
import com.bank.customer.support.CustomerErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 외부망 없는 본인확인 목 — 주민등록번호에서 CI·생년월일·성별을 결정적으로 파생한다.
 *
 * <p>전화번호 해시를 CI 로 쓰던 기존 placeholder 와 달리, 주민번호 기반이라 사람 단위로 안정적이다
 * (번호 변경과 무관). CI = Base64(SHA-256(rrn + secret)) 로 평문 주민번호를 노출하지 않는다.
 * 실제 NICE/KCB 연동으로 교체 가능하도록 {@link IdentityVerificationPort} 뒤에 둔다.
 */
@Component
public class MockIdentityVerificationProvider implements IdentityVerificationPort {

    private final String ciSecret;

    public MockIdentityVerificationProvider(
            @Value("${customer.identity.ci-secret:dev-ci-secret-change-in-production}") String ciSecret,
            org.springframework.core.env.Environment environment) {
        com.bank.common.security.DevSecretGuard.verify("customer.identity.ci-secret", ciSecret, environment);
        this.ciSecret = ciSecret;
    }

    @Override
    public VerifiedIdentity resolve(String name, String rrn, String phoneNumber) {
        if (name == null || name.isBlank() || rrn == null || !rrn.matches("\\d{13}")) {
            throw new BusinessException(CustomerErrorCode.CUST_097);
        }

        int genderDigit = rrn.charAt(6) - '0';
        String century = switch (genderDigit) {
            case 1, 2, 5, 6 -> "19";
            case 3, 4, 7, 8 -> "20";
            case 9, 0       -> "18";
            default         -> throw new BusinessException(CustomerErrorCode.CUST_097);
        };
        String nationalityType = (genderDigit == 5 || genderDigit == 6 || genderDigit == 7 || genderDigit == 8)
                ? "FOREIGN" : "DOMESTIC";
        String genderCode = (genderDigit % 2 == 1) ? "M" : "F";

        String birthDate = century + rrn.substring(0, 6);   // YYYYMMDD
        validateBirthDate(birthDate);

        return new VerifiedIdentity(deriveCi(rrn), birthDate, genderCode, nationalityType);
    }

    /** CI = Base64(SHA-256(rrn + secret)) — 결정적·평문 비노출. */
    private String deriveCi(String rrn) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((rrn + ciSecret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);   // 44자, ci_value(88) 이내
        } catch (Exception e) {
            throw new IllegalStateException("CI 파생 실패", e);
        }
    }

    private void validateBirthDate(String yyyymmdd) {
        int month = Integer.parseInt(yyyymmdd.substring(4, 6));
        int day   = Integer.parseInt(yyyymmdd.substring(6, 8));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            throw new BusinessException(CustomerErrorCode.CUST_097);
        }
    }
}
