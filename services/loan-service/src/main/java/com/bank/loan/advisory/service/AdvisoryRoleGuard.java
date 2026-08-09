package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 어드바이저리 API 권한 가드.
 *
 * <p><b>무엇이 바뀌었나.</b> 예전에는 컨트롤러가 {@code X-Actor-Role} 헤더를 받아 이
 * 빈에 넘겼다. 그 헤더는 이 코드에서만 쓰던 이름이라 게이트웨이가 지우는 목록에
 * 없었고, 결국 <b>호출자가 스스로 적은 값</b>으로 권한을 판정했다. 검사는 22곳에
 * 있었지만 전부 같은 거짓말을 입력으로 받았다 — 검사가 있어서 더 위험한 상태였다.
 * 코드를 읽으면 보호되고 있는 것처럼 보이기 때문이다.
 *
 * <p>지금은 {@code GatewayHeaderAuthFilter} 가 검증된 {@code X-User-Role} 로 세운
 * SecurityContext 를 읽는다. 헤더를 인자로 받지 않으므로 <b>컨트롤러가 실수로
 * 요청 값을 넘길 자리가 없다</b>.
 */
@Component
public class AdvisoryRoleGuard {

    /** 모든 role 허용 (REVIEWER/AUDITOR/ADMIN). 본인 필터링은 서비스 단에서 처리. */
    public AdvisoryViewerRole requireAnyRole() {
        return currentRole();
    }

    public AdvisoryViewerRole requireAuditorOrAdmin() {
        return requireOneOf(EnumSet.of(AdvisoryViewerRole.AUDITOR, AdvisoryViewerRole.ADMIN));
    }

    public AdvisoryViewerRole requireAdmin() {
        return requireOneOf(EnumSet.of(AdvisoryViewerRole.ADMIN));
    }

    public AdvisoryViewerRole requireOneOf(Set<AdvisoryViewerRole> allowed) {
        AdvisoryViewerRole role = currentRole();
        if (!allowed.contains(role)) {
            throw new BusinessException(CommonErrorCode.COMMON_403,
                    "role=" + role + " allowed=" + Arrays.toString(allowed.toArray()));
        }
        return role;
    }

    /**
     * 지금 요청의 열람 등급.
     *
     * <p>인증이 없으면 REVIEWER 다. 게이트웨이를 거치지 않은 요청은 X-User-Id 가
     * 없어 SecurityContext 가 비는데, 그때 권한을 올려 주면 우회가 곧 승격이 된다.
     */
    private AdvisoryViewerRole currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return AdvisoryViewerRole.REVIEWER;
        }
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return AdvisoryViewerRole.from(authorities);
    }
}
