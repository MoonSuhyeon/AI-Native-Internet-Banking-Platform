package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 어드바이저리 API 권한 가드. 현재는 `X-Actor-Role` 헤더 기반으로 동작하며,
 * Spring Security 도입 시 본 빈 내부에서 SecurityContext 의 role 을 읽도록 교체한다.
 *
 * 컨트롤러는 본 빈의 require* 메서드를 호출해 role 검증 + 파싱을 일원화한다.
 */
@Component
public class AdvisoryRoleGuard {

    /** 모든 role 허용 (REVIEWER/AUDITOR/ADMIN). 본인 필터링은 서비스 단에서 처리. */
    public AdvisoryViewerRole requireAnyRole(String header) {
        return AdvisoryViewerRole.parse(header);
    }

    public AdvisoryViewerRole requireAuditorOrAdmin(String header) {
        return requireOneOf(header, EnumSet.of(AdvisoryViewerRole.AUDITOR, AdvisoryViewerRole.ADMIN));
    }

    public AdvisoryViewerRole requireAdmin(String header) {
        return requireOneOf(header, EnumSet.of(AdvisoryViewerRole.ADMIN));
    }

    public AdvisoryViewerRole requireOneOf(String header, Set<AdvisoryViewerRole> allowed) {
        AdvisoryViewerRole role = AdvisoryViewerRole.parse(header);
        if (!allowed.contains(role)) {
            throw new BusinessException(CommonErrorCode.COMMON_403,
                    "role=" + role + " allowed=" + Arrays.toString(allowed.toArray()));
        }
        return role;
    }
}
