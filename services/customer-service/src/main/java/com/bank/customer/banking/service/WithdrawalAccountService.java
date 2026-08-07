package com.bank.customer.banking.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.web.BusinessException;
import com.bank.customer.banking.domain.WithdrawalAccount;
import com.bank.customer.banking.dto.RegisterWithdrawalAccountRequest;
import com.bank.customer.banking.dto.UpdateOrderRequest;
import com.bank.customer.banking.dto.WithdrawalAccountResponse;
import com.bank.customer.banking.repository.WithdrawalAccountRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Transactional
public class WithdrawalAccountService {

    /** 본행(AXful) 은행코드. 본행 보유계좌는 이체 시 자동 노출되므로 출금계좌 등록 대상이 아니다. */
    private static final String HOME_BANK_CODE = "AXFUL";

    private final WithdrawalAccountRepository withdrawalAccountRepository;

    @Transactional(readOnly = true)
    public List<WithdrawalAccountResponse> list(Long customerId) {
        return withdrawalAccountRepository
                .findByCustomerIdAndDeletedAtIsNullOrderByPriorityOrderAsc(customerId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public WithdrawalAccountResponse register(Long customerId, RegisterWithdrawalAccountRequest request) {
        if (HOME_BANK_CODE.equalsIgnoreCase(request.bankCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_052);
        }
        if (withdrawalAccountRepository.existsByCustomerIdAndAccountNumberAndDeletedAtIsNull(
                customerId, request.accountNumber())) {
            throw new BusinessException(CustomerErrorCode.CUST_051);
        }

        // 현재 등록된 계좌 수를 순위 기본값으로
        long count = withdrawalAccountRepository
                .findByCustomerIdAndDeletedAtIsNullOrderByPriorityOrderAsc(customerId)
                .size();

        WithdrawalAccount saved = withdrawalAccountRepository.save(WithdrawalAccount.builder()
                .customerId(customerId)
                .accountNumber(request.accountNumber())
                .bankCode(request.bankCode())
                .bankName(request.bankName())
                .accountHolderName(request.accountHolderName())
                .accountAlias(request.accountAlias())
                .registrationType("ONLINE")
                .priorityOrder((short) count)
                .registeredAt(OffsetDateTime.now())
                .build());

        return toResponse(saved);
    }

    public void delete(Long customerId, Long withdrawalAccountId) {
        WithdrawalAccount account = withdrawalAccountRepository
                .findByWithdrawalAccountIdAndCustomerIdAndDeletedAtIsNull(withdrawalAccountId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_050));
        account.softDelete(customerId);
    }

    public void updateOrder(Long customerId, UpdateOrderRequest request) {
        List<WithdrawalAccount> accounts = withdrawalAccountRepository
                .findByCustomerIdAndDeletedAtIsNullOrderByPriorityOrderAsc(customerId);

        for (int i = 0; i < request.orderedIds().size(); i++) {
            final int order = i;
            final Long id = request.orderedIds().get(i);
            accounts.stream()
                    .filter(a -> a.getWithdrawalAccountId().equals(id))
                    .findFirst()
                    .ifPresent(a -> a.updatePriorityOrder((short) order));
        }
    }

    private WithdrawalAccountResponse toResponse(WithdrawalAccount a) {
        return new WithdrawalAccountResponse(
                a.getWithdrawalAccountId(),
                a.getAccountNumber(),
                a.getBankCode(),
                a.getBankName(),
                a.getAccountHolderName(),
                a.getAccountAlias(),
                a.getRegistrationType(),
                a.getPriorityOrder(),
                a.getRegisteredAt() != null ? BusinessDate.dateOf(a.getRegisteredAt()).toString() : ""
        );
    }
}
