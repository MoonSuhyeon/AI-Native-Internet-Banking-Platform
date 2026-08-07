package com.bank.customer.cert.repository;

import com.bank.customer.cert.domain.AuthMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthMethodRepository extends JpaRepository<AuthMethod, Long> {

    List<AuthMethod> findByCustomerIdAndDeletedAtIsNull(Long customerId);

    List<AuthMethod> findByCustomerIdAndAuthMethodStatusCodeAndDeletedAtIsNull(
            Long customerId, String statusCode);

    Optional<AuthMethod> findByAuthMethodIdAndCustomerIdAndDeletedAtIsNull(
            Long authMethodId, Long customerId);

    Optional<AuthMethod> findByCustomerIdAndPrimaryAuthMethodYnAndDeletedAtIsNull(
            Long customerId, Boolean primaryYn);
}
