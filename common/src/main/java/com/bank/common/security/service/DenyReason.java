package com.bank.common.security.service;

/**
 * 인가를 거절한 이유.
 *
 * <p><b>왜 이유를 열거하는가.</b> 거절 감사에 자유 문자열을 남기면 집계가 안 된다.
 * "권한 없는 서비스가 반복 호출했다" 를 알아채려면 같은 사건이 같은 값으로 쌓여야 한다.
 *
 * <p><b>호출자에게는 이 값을 그대로 주지 않는다.</b> 어느 단계에서 막혔는지 알려 주면
 * 권한 경계를 탐색하는 데 쓸 수 있다. 응답은 한 가지 형태로 돌려주고, 구분은
 * 감사 로그에만 남긴다.
 */
public enum DenyReason {

    /** 자격증명이 없거나 아는 서비스와 맞지 않는다. 신원 자체를 세우지 못했다. */
    UNKNOWN_CREDENTIAL,

    /** 신원은 확인됐으나 그 서비스가 정지 상태다. */
    SERVICE_SUSPENDED,

    /** 요청한 작업 이름을 모른다. */
    UNKNOWN_OPERATION,

    /** 그 작업에 대한 권한이 부여된 적 없다. */
    NO_PERMISSION,

    /** 권한이 있었으나 회수됐다. */
    PERMISSION_REVOKED,

    /** 건당 한도를 넘었다. */
    AMOUNT_EXCEEDED,

    /** 허용되지 않은 송신계좌다. */
    ACCOUNT_NOT_ALLOWED
}
