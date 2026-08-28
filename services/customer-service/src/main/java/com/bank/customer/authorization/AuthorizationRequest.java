package com.bank.customer.authorization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 인가 질의.
 *
 * <p>"이 직원을 알려 달라" 가 아니라 <b>"이 직원이 이 자원에 이 행위를 해도 되는가"</b> 다.
 * 직원 조회 API 로 만들면 부르는 쪽마다 판단 규칙을 다시 구현하게 되고,
 * 그 규칙들이 서로 조금씩 달라진다.
 *
 * @param employeeId       게이트웨이가 JWT 에서 확정한 직원 식별자
 * @param resource         DEPOSIT_ACCOUNT · DEPOSIT_TRANSACTION · DEPOSIT_CONTRACT
 * @param action           READ (지금은 읽기만 다룬다)
 * @param targetCustomerId 조회 대상 고객. 감사에 남기려고 받는다
 * @param reason           조회 사유. 고객 금융정보 열람에는 필수
 */
public record AuthorizationRequest(
        @NotNull Long employeeId,
        @NotBlank String resource,
        @NotBlank String action,
        String targetCustomerId,
        String reason
) {}
