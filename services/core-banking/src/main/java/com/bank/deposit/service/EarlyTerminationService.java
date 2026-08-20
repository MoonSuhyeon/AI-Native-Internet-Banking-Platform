package com.bank.deposit.service;

import com.bank.deposit.domain.EarlyTerminationEstimator;
import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.EarlyTerminationRate;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.enums.PaymentStatus;
import com.bank.deposit.dto.response.EarlyTerminationEstimateResponse;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.EarlyTerminationRateRepository;
import com.bank.deposit.repository.PaymentScheduleRepository;
import com.bank.deposit.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 해지 예상 명세.
 *
 * <p>해지 화면의 "해지상세조회" 가 여기로 온다. 중도해지는 되돌릴 수 없는데 결과를
 * 미리 볼 수 없었다 — 고객은 얼마를 손해 보는지 모른 채 "해지" 를 눌러야 했다.
 *
 * <p>이 서비스는 아무것도 바꾸지 않는다. 읽기 전용이라는 것이 중요하다 — 조회가
 * 상태를 건드리면 "확인만 해 봤는데 해지됐다" 가 가능해진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EarlyTerminationService {

    private final AccountRepository accountRepository;
    private final ContractRepository contractRepository;
    private final ProductRepository productRepository;
    private final PaymentScheduleRepository scheduleRepository;
    private final EarlyTerminationRateRepository rateRepository;

    public EarlyTerminationEstimateResponse estimate(Long accountId, LocalDate on) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        Contract contract = contractRepository.findById(
                        account.getContractId() == null ? -1L : account.getContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        Product product = productRepository.findById(contract.getProductId()).orElse(null);
        String productType = product != null && product.getProductType() != null
                ? product.getProductType().name()
                : null;

        List<EarlyTerminationRate> policy = productType == null
                ? rateRepository.findDefaults()
                : firstNonEmpty(rateRepository.findForProductType(productType),
                                rateRepository.findDefaults());

        var estimate = EarlyTerminationEstimator.estimate(
                depositsOf(account, contract),
                contract.getFinalInterestRate(),
                contract.getAppliedTaxRate(),
                contract.getStartedAt(),
                contract.getMaturityAt(),
                on,
                policy);

        boolean allowed = product == null || Boolean.TRUE.equals(product.getIsEarlyTerminationAllowed());

        return EarlyTerminationEstimateResponse.of(
                accountId, account.getAccountNumber(), contract, estimate, on,
                allowed || !estimate.earlyTermination());
    }

    /**
     * 예치 건들.
     *
     * <p>적금은 납입 회차마다 예치 기간이 다르므로 회차별로 쪼갠다. 전체 납입액에
     * 전체 기간을 곱하면 나중에 낸 돈까지 처음부터 예치된 것으로 쳐서 이자가
     * 부풀려진다 — 고객에게 실제보다 많은 금액을 예고하게 된다.
     */
    private List<EarlyTerminationEstimator.Deposit> depositsOf(Account account, Contract contract) {
        List<PaymentSchedule> paid = scheduleRepository
                .findByContractIdOrderByPaymentRound(contract.getContractId()).stream()
                .filter(s -> s.getStatus() == PaymentStatus.PAID)
                .filter(s -> s.getPaidAt() != null)
                .toList();

        if (!paid.isEmpty()) {
            return paid.stream()
                    .map(s -> new EarlyTerminationEstimator.Deposit(
                            s.getActualAmount() != null ? s.getActualAmount() : s.getScheduledAmount(),
                            s.getPaidAt().toLocalDate()))
                    .toList();
        }

        // 예금처럼 한 번에 넣은 계좌, 또는 스케줄 없이 자유 납입한 계좌.
        // 잔액 전체를 계약 시작일에 넣은 것으로 본다 — 이 경우 이자가 실제보다
        // 많이 나올 수 있어 화면이 "예상" 임을 밝힌다.
        long principal = account.getBalance() != null ? account.getBalance() : 0L;
        return List.of(new EarlyTerminationEstimator.Deposit(principal, contract.getStartedAt()));
    }

    private static <T> List<T> firstNonEmpty(List<T> a, List<T> b) {
        return a.isEmpty() ? b : a;
    }
}
