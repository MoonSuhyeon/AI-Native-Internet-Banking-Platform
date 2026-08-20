package com.bank.customer.notification.repository;

import com.bank.customer.notification.domain.AlertChannel;
import com.bank.customer.notification.domain.DepositAlertSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepositAlertSubscriptionRepository
        extends JpaRepository<DepositAlertSubscription, Long> {

    List<DepositAlertSubscription> findByCustomerIdOrderBySubscriptionIdAsc(Long customerId);

    Optional<DepositAlertSubscription> findByCustomerIdAndAccountNumberAndChannel(
            Long customerId, String accountNumber, AlertChannel channel);

    Optional<DepositAlertSubscription> findBySubscriptionIdAndCustomerId(
            Long subscriptionId, Long customerId);

    /** 발송 시점 조회. 입금 이벤트가 생기면 여기서 수신자를 찾는다. */
    List<DepositAlertSubscription> findByAccountNumberAndActiveIsTrue(String accountNumber);
}
