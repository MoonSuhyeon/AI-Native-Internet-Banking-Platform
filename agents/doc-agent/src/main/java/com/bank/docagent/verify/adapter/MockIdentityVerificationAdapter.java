package com.bank.docagent.verify.adapter;

import com.bank.docagent.verify.port.IdentityVerificationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 어댑터 — 실 API 키 미보유 환경(PoC·테스트) 에서 활성화.
 * DRIVER_LICENSE_API_ENABLED=false(기본값) 이면 이 어댑터가 등록된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "doc-agent.identity-verify.driver-license.enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class MockIdentityVerificationAdapter implements IdentityVerificationPort {

    @Override
    public VerifyResult verify(VerifyType type, String name, String idNumber, String birthDate) {
        log.info("[MOCK] 진위확인 SKIPPED: type={}", type);
        return VerifyResult.SKIPPED;
    }
}
