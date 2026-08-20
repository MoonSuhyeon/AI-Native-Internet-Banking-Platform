package com.bank.customer.notification.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.notification.domain.AlertChannel;
import com.bank.customer.notification.domain.DepositAlertSubscription;
import com.bank.customer.notification.repository.DepositAlertSubscriptionRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 입금통보 신청·해지.
 *
 * <p>같은 계좌를 같은 채널로 두 번 신청하면 알림이 두 번 간다. 유니크 제약이 그걸
 * 막는데, 해지한 신청도 행으로 남아 있어 그냥 저장하면 제약에 걸린다. 그래서 이미
 * 있는 행을 찾아 다시 켠다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DepositAlertService {

    private final DepositAlertSubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public List<DepositAlertSubscription> list(Long customerId) {
        return subscriptionRepository.findByCustomerIdOrderBySubscriptionIdAsc(customerId);
    }

    public DepositAlertSubscription subscribe(
            Long customerId, String accountNumber, AlertChannel channel,
            String contact, long minAmount) {

        OffsetDateTime now = OffsetDateTime.now();
        try {
            return subscriptionRepository
                    .findByCustomerIdAndAccountNumberAndChannel(customerId, accountNumber, channel)
                    .map(existing -> {
                        if (existing.isActive()) {
                            throw new BusinessException(CustomerErrorCode.CUST_171);
                        }
                        existing.reopen(contact, minAmount, now);
                        return existing;
                    })
                    .orElseGet(() -> subscriptionRepository.save(DepositAlertSubscription.open(
                            customerId, accountNumber, channel, contact, minAmount, now)));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CustomerErrorCode.CUST_172, e.getMessage());
        }
    }

    public void unsubscribe(Long customerId, Long subscriptionId) {
        DepositAlertSubscription subscription = subscriptionRepository
                .findBySubscriptionIdAndCustomerId(subscriptionId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_170));
        subscription.close(OffsetDateTime.now());
    }
}
