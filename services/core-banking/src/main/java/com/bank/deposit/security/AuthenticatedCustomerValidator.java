package com.bank.deposit.security;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이가 주입한 인증 고객 ID 로 본인 데이터 접근인지 검증한다.
 *
 * <p><b>왜 계좌 기준 검증이 따로 필요한가.</b> 원래 이 검증기는 요청 본문·쿼리에
 * {@code customerId} 가 있을 때만 동작했다. 그래서 대상을 {@code accountId} 나
 * {@code transactionId} 로 지목하는 API — 이체·출금·거래취소·계좌 설정 변경 — 는
 * 규약 자체가 커버하지 못했고, 유효한 토큰만 있으면 남의 계좌로 자금을 움직일 수
 * 있었다. 계좌↔고객 매핑을 아는 곳은 이 도메인뿐이므로 여기서 막는다.
 *
 * <p><b>헤더가 없으면 통과시키는 이유.</b> payment 오케스트레이터가 서비스 간 호출로
 * {@code /api/transactions/withdraw}·{@code /deposit}·{@code /{id}/cancel} 과
 * {@code /api/accounts/by-number} 를 부른다. 이 호출에는 고객 신원이 없다.
 * "게이트웨이 1차 검증 + 내부망 신뢰" 라는 기존 전제를 따르되, 신원이 실려 온 요청은
 * 반드시 대조한다.
 *
 * <p>⚠ 한계: 헤더 부재를 내부 호출로 간주하므로, 이 서비스 포트에 직접 닿을 수 있으면
 * 헤더를 빼고 우회할 수 있다. 운영 compose 는 게이트웨이만 외부에 노출하므로 지금은
 * 막혀 있으나, 근본 해결은 서비스 간 인증(mTLS·공유 시크릿)이나 내부 전용 경로 분리다.
 * 고객만 쓰는 이체는 {@link #requireAccountOwner} 로 헤더를 필수화해 이 예외를 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AuthenticatedCustomerValidator {

    public static final String CUSTOMER_ID_HEADER = "X-Customer-Id";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public void validate(String authenticatedCustomerId, String requestedCustomerId) {
        if (requestedCustomerId == null || requestedCustomerId.isBlank()) {
            return;
        }
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "인증된 고객 ID가 필요합니다.");
        }
        if (!authenticatedCustomerId.equals(requestedCustomerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 고객의 데이터에는 접근할 수 없습니다.");
        }
    }

    /**
     * 계좌 소유자 검증 — 신원이 실려 왔을 때만.
     *
     * <p>서비스 간 호출(신원 없음)은 통과시킨다. 고객 요청은 게이트웨이가 항상
     * {@code X-Customer-Id} 를 주입하므로 여기서 걸린다.
     */
    public void validateAccountOwner(String authenticatedCustomerId, Long accountId) {
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            return;
        }
        requireAccountOwner(authenticatedCustomerId, accountId);
    }

    /**
     * 계좌 소유자 검증 — 신원을 필수로 요구한다(fail-closed).
     *
     * <p>고객만 호출하는 API(이체·적금납입)에 쓴다. 서비스 간 호출이 없는 경로라
     * 신원 없는 요청을 허용할 이유가 없다.
     */
    public void requireAccountOwner(String authenticatedCustomerId, Long accountId) {
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "인증된 고객 ID가 필요합니다.");
        }
        if (accountId == null) {
            return;
        }
        String owner = accountRepository.findById(accountId)
                .map(Account::getCustomerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!authenticatedCustomerId.equals(owner)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 고객의 계좌에는 접근할 수 없습니다.");
        }
    }

    /**
     * 계약에 딸린 계좌의 소유자 검증 — 신원을 필수로 요구한다.
     *
     * <p>해지는 되돌릴 수 없다. 계약 아이디는 연속된 숫자라, 검증이 없으면 유효한
     * 토큰만 있으면 남의 예적금을 해지할 수 있었다.
     *
     * <p>계좌 기준 검증과 달리 신원 없는 호출을 허용하지 않는다 — 계약 해지를 부르는
     * 서비스 간 호출이 없기 때문이다.
     */
    public void requireContractOwner(String authenticatedCustomerId, Long contractId) {
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "인증된 고객 ID가 필요합니다.");
        }
        String owner = accountRepository.findByContractId(contractId)
                .map(Account::getCustomerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!authenticatedCustomerId.equals(owner)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 고객의 계약에는 접근할 수 없습니다.");
        }
    }

    /** 거래가 속한 계좌의 소유자 검증 — 신원이 실려 왔을 때만. */
    public void validateTransactionOwner(String authenticatedCustomerId, Long transactionId) {
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            return;
        }
        Long accountId = transactionRepository.findById(transactionId)
                .map(Transaction::getAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
        requireAccountOwner(authenticatedCustomerId, accountId);
    }
}
