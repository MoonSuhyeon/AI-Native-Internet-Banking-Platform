package com.bank.payment.security;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 서비스 인가 레지스트리 접근.
 *
 * <p>신원·권한·계좌정책·감사를 한 매퍼에 둔다. 셋으로 쪼개면 파일은 늘지만 관심사는
 * 하나다 — "이 호출을 허용할 것인가" 를 판정하는 데 필요한 전부다.
 */
public interface ServiceAuthorizationMapper {

    /**
     * 자격증명 해시로 신원을 찾는다.
     *
     * <p>신원은 주장이 아니라 자격증명에서 나온다. 호출자가 자기가 누구인지 말하는
     * 헤더를 두지 않는 이유다 — 토큰 하나를 가진 무엇이든 다른 서비스를 사칭할 수 있다.
     *
     * @return 아는 자격증명이 아니면 {@code null}
     */
    ServicePrincipal findPrincipalByCredentialHash(@Param("credentialHash") String credentialHash);

    /**
     * 그 서비스의 그 작업 권한을 찾는다. 회수된 권한도 함께 돌려준다.
     *
     * <p>회수된 것을 걸러서 주지 않는 이유는, "권한이 없었다" 와 "있었는데 회수됐다" 를
     * 감사에서 구분해야 하기 때문이다.
     *
     * @return 부여된 적 없으면 {@code null}
     */
    ServicePermission findPermission(@Param("serviceId") String serviceId,
                                     @Param("operation") String operation);

    /**
     * 그 권한에 허용된 송신계좌 목록.
     *
     * @return 비어 있으면 계좌를 제한하지 않는다는 뜻이다
     */
    List<String> findAllowedAccounts(@Param("permissionId") Long permissionId);

    /** 판정 감사를 남긴다. INSERT 전용 테이블이다. */
    void insertAuthorizationLog(ServiceAuthorizationLogEntry entry);
}
