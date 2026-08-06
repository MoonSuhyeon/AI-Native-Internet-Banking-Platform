package com.bank.deposit.security;

import com.bank.deposit.client.CustomerServiceClient;
import com.bank.deposit.client.dto.TransactionApprovalVerifyRequest;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 이체 직전 승인 토큰(step-up) 확인.
 *
 * <p><b>왜 필요한가.</b> 로그인은 "누구인가"를 묻고 이체는 "지금 이 금액을 이 계좌로 보내는
 * 것이 맞는가"를 묻는다. 소유권 검증까지는 "본인 계좌에서만 나간다"를 보장하지만,
 * 세션이 탈취되면 그것만으로는 막지 못한다. 마지막 방어선이 이 확인이다.
 *
 * <p><b>왜 여기서 PIN 을 받지 않는가.</b> 인증수단의 비밀(인증서·PIN)은 인증보안계에 있다.
 * 이 도메인이 직접 대조하면 비밀이 두 곳에 퍼진다. 여기서는 토큰 한 장만 확인한다.
 *
 * <p><b>단계적 도입.</b> {@code deposit.transfer.approval.required} 가 false 인 동안은
 * 토큰이 있을 때만 검증한다. 프런트·챗봇이 발급 단계를 붙이기 전에 필수로 돌리면 이체가
 * 전부 죽기 때문이다. 미사용 호출이 0 인 것을 로그로 확인한 뒤 true 로 바꾼다.
 * (설계: docs/plan/transfer-step-up-auth.md)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferApprovalGate {

    private final CustomerServiceClient customerServiceClient;

    @Value("${deposit.transfer.approval.required:false}")
    private boolean required;

    /**
     * <p>계좌를 <b>번호</b>로 대조하는 이유: 타행이체(결제계)는 accountId 를 모르고 번호로만
     * 거래를 지목한다. 두 경로가 같은 승인 체계를 쓰려면 공통으로 아는 값이어야 한다.
     *
     * @param approvalToken 없으면 null·빈 문자열
     * @throws BusinessException 필수인데 없거나(TRANSFER_APPROVAL_REQUIRED),
     *                           검증에 실패한 경우(TRANSFER_APPROVAL_INVALID)
     */
    public void verify(String approvalToken, String fromAccountNo, String toAccountNo, BigDecimal amount) {
        if (approvalToken == null || approvalToken.isBlank()) {
            if (required) {
                throw new BusinessException(ErrorCode.TRANSFER_APPROVAL_REQUIRED);
            }
            // 필수 전환 판단 근거가 되는 로그다. 이 줄이 사라져야 required=true 로 바꿀 수 있다.
            log.info("이체 승인 토큰 없이 처리됨 (과도기) fromAccountNo={}", fromAccountNo);
            return;
        }

        try {
            customerServiceClient.verifyTransactionApproval(
                    new TransactionApprovalVerifyRequest(approvalToken, fromAccountNo, toAccountNo, amount));
        } catch (Exception e) {
            // 인증보안계가 거부했거나 통신에 실패했다. 어느 쪽이든 승인은 확인되지 않았다 —
            // 확인되지 않은 승인으로 돈을 보내지 않는다(fail-closed).
            log.warn("이체 승인 검증 실패 fromAccountNo={} reason={}", fromAccountNo, e.toString());
            throw new BusinessException(ErrorCode.TRANSFER_APPROVAL_INVALID);
        }
    }
}
